package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDispatch_요청구성(t *testing.T) {
	var gotPath, gotAuth, gotEventType, gotSha string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotAuth = r.Header.Get("Authorization")
		var body struct {
			EventType     string            `json:"event_type"`
			ClientPayload map[string]string `json:"client_payload"`
		}
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &body)
		gotEventType = body.EventType
		gotSha = body.ClientPayload["deploy_sha"]
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	c := newDispatchClient(srv.URL, "depromeet/18th-team4-server", "tok123")
	err := c.dispatch(&Incident{DeploySha: "6119aa2", Fingerprint: "fp"})
	if err != nil {
		t.Fatalf("dispatch 실패: %v", err)
	}
	if gotPath != "/repos/depromeet/18th-team4-server/dispatches" {
		t.Errorf("path=%q", gotPath)
	}
	if gotAuth != "Bearer tok123" {
		t.Errorf("auth=%q", gotAuth)
	}
	if gotEventType != "incident-analysis" {
		t.Errorf("event_type=%q", gotEventType)
	}
	if gotSha != "6119aa2" {
		t.Errorf("deploy_sha=%q", gotSha)
	}
}

func TestDispatch_비정상응답_에러(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	c := newDispatchClient(srv.URL, "o/r", "t")
	if err := c.dispatch(&Incident{}); err == nil {
		t.Fatal("401인데 에러 안 남")
	}
}
