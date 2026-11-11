// handbook-lite: single-binary frontend host + API gateway.
// Serves the embedded Vue dist and transparently proxies /api/* to the
// upstream Spring Boot backend (SSE streaming friendly). Image < 50 MB.
package main

import (
	"embed"
	"io/fs"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"time"
)

//go:embed all:dist
var distFS embed.FS

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
	proxy := httputil.NewSingleHostReverseProxy(target)
	orig := proxy.Director
	proxy.Director = func(r *http.Request) {
		orig(r)
		r.Host = target.Host
		r.URL.Path = strings.TrimPrefix(r.URL.Path, "/api")
		if r.URL.Path == "" || !strings.HasPrefix(r.URL.Path, "/") {
			r.URL.Path = "/" + r.URL.Path
		}
	}
	proxy.FlushInterval = -1 // flush immediately: required for SSE chat streaming

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.Handle("/api/", proxy)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		p := strings.TrimPrefix(r.URL.Path, "/")
		if p != "" {
			if f, err := sub.Open(p); err == nil {
				_ = f.Close()
				static.ServeHTTP(w, r)
				return
			}
		}
		// SPA fallback
		r.URL.Path = "/"
		static.ServeHTTP(w, r)
	})

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		// no WriteTimeout: SSE chats can stream for minutes
	}
	log.Printf("handbook-lite listening on :%s -> upstream %s", port, upstream)
	log.Fatal(srv.ListenAndServe())
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
