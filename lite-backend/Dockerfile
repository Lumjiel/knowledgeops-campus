# syntax=docker/dockerfile:1.7
# ---- frontend dist is built on the dev machine and committed/copied in ----
FROM golang:1.23-alpine AS builder
WORKDIR /build
COPY go.mod ./
COPY main.go ./
COPY dist ./dist
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/handbook-lite .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates && adduser -S -D app
USER app
COPY --from=builder /out/handbook-lite /app/handbook-lite
EXPOSE 8091
ENTRYPOINT ["/app/handbook-lite"]
