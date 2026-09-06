package main

import (
	"log"
	"net/http"
	"os"
)

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func mustEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		log.Fatalf("환경변수 %s 필요", key)
	}
	return v
}

func newMux(s *server) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("POST /slack/incident-actions", s.handleAction)
	return mux
}

func main() {
	s := &server{
		signingSecret: mustEnv("SLACK_SIGNING_SECRET"),
		dispatcher: newDispatchClient(
			getenv("GITHUB_API_URL", "https://api.github.com"),
			mustEnv("GITHUB_REPOSITORY"),
			mustEnv("INCIDENT_DISPATCH_TOKEN"),
		),
	}
	addr := ":" + getenv("PORT", "8090")
	log.Printf("slack-receiver 기동 %s", addr)
	log.Fatal(http.ListenAndServe(addr, newMux(s)))
}
