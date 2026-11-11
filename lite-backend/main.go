// handbook-lite: single-binary frontend host + API gateway.
// Serves the embedded Vue dist and transparently proxies /api/* to the
// upstream Spring Boot backend. For SSE (text/event-stream) responses a
// ": keepalive" comment line is emitted every 8s while upstream is idle,
// so mobile carrier NAT / power-saving does not kill the connection
// during long LLM generations. Image < 50 MB.
package main

import (
	"embed"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

//go:embed all:dist
var distFS embed.FS

var upstreamClient = &http.Client{
	Timeout: 0, // no global timeout: SSE chats stream for minutes
	Transport: &http.Transport{
		DialContext:       (&net.Dialer{Timeout: 15 * time.Second}).DialContext,
		MaxIdleConns:      100,
		IdleConnTimeout:   90 * time.Second,
	},
}

const keepaliveInterval = 8 * time.Second

// hop-by-hop headers that must not be forwarded.
var hopHeaders = map[string]bool{
	"Connection": true, "Proxy-Connection": true, "Keep-Alive": true,
	"Proxy-Authenticate": true, "Proxy-Authorization": true, "Te": true,
	"Trailer": true, "Transfer-Encoding": true, "Upgrade": true,
}

func handleAPI(w http.ResponseWriter, r *http.Request, target *url.URL) {
	upURL := *r.URL
	upURL.Scheme = target.Scheme
	upURL.Host = target.Host
	upURL.Path = strings.TrimPrefix(r.URL.Path, "/api")
	if upURL.Path == "" || !strings.HasPrefix(upURL.Path, "/") {
		upURL.Path = "/" + upURL.Path
	}

	upReq, err := http.NewRequestWithContext(r.Context(), r.Method, upURL.String(), r.Body)
	if err != nil {
		http.Error(w, "bad upstream request", http.StatusBadGateway)
		return
	}
	for k, vv := range r.Header {
		if hopHeaders[k] || k == "Host" {
			continue
		}
		for _, v := range vv {
			upReq.Header.Add(k, v)
		}
	}
	upReq.Host = target.Host

	resp, err := upstreamClient.Do(upReq)
	if err != nil {
		log.Printf("upstream error: %v", err)
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// copy response headers (skip hop-by-hop and Content-Length: we re-chunk)
	for k, vv := range resp.Header {
		if hopHeaders[k] || k == "Content-Length" {
			continue
		}
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)

	flusher, canFlush := w.(http.Flusher)
	if !canFlush {
		_, _ = io.Copy(w, resp.Body)
		return
	}

	isSSE := strings.Contains(strings.ToLower(resp.Header.Get("Content-Type")), "text/event-stream")

	var mu sync.Mutex
	done := make(chan struct{})

	go func() {
		defer close(done)
		buf := make([]byte, 32*1024)
		for {
			n, err := resp.Body.Read(buf)
			if n > 0 {
				mu.Lock()
				_, werr := w.Write(buf[:n])
				flusher.Flush()
				mu.Unlock()
				if werr != nil {
					return // client went away
				}
			}
			if err != nil {
				return
			}
		}
	}()

	tk := time.NewTicker(keepaliveInterval)
	defer tk.Stop()
	for {
		select {
		case <-done:
			return
		case <-tk.C:
			if isSSE {
				// SSE comment line: spec-compliant, ignored by clients,
				// keeps bytes flowing through NAT/proxies while the
				// upstream LLM is silently thinking.
				mu.Lock()
				_, _ = w.Write([]byte(": keepalive\n\n"))
				flusher.Flush()
				mu.Unlock()
			}
		}
	}
}

func main() {
	port := getenv("PORT", "8091")
	upstream := getenv("UPSTREAM", "http://127.0.0.1:8080")

	sub, err := fs.Sub(distFS, "dist")
	if err != nil {
		log.Fatal("embed dist: ", err)
	}
	static := http.FileServer(http.FS(sub))

	target, err := url.Parse(upstream)
	if err != nil {
		log.Fatal("upstream url: ", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		handleAPI(w, r, target)
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(r.URL.Path, "/")
		if p != "" {
			if f, err := sub.Open(p); err == nil {
				_ = f.Close()
				// hashed assets are immutable: cache for a year
				if strings.HasPrefix(p, "assets/") {
					w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
				}
				static.ServeHTTP(w, r)
				return
			}
		}
		// SPA fallback: index.html must always revalidate, otherwise a
		// cached stale index keeps loading old hashed assets after a deploy
		// (the "old tab stays broken, incognito works" failure mode).
		w.Header().Set("Cache-Control", "no-cache")
		r.URL.Path = "/"
		static.ServeHTTP(w, r)
	})

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		// no WriteTimeout: SSE chats stream for minutes
	}
	log.Printf("handbook-lite listening on :%s -> upstream %s (sse keepalive %s)", port, upstream, keepaliveInterval)
	log.Fatal(srv.ListenAndServe())
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
