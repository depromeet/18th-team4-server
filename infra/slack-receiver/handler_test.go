package main

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestHandler_정상요청_dispatch호출(t *testing.T) {
	var mu sync.Mutex
	dispatched := false
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		dispatched = true
		mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	}))
	defer gh.Close()

	s := &server{
		signingSecret: testSecret,
		dispatcher:    newDispatchClient(gh.URL, "o/r", "t"),
	}
	body := sampleBody()
	ts := "1600000000"
	s.now = func() time.Time { return time.Unix(1600000000, 0) }
	req := newSignedRequest(ts, body, sign(testSecret, ts, body))
	rec := httptest.NewRecorder()
	s.handleAction(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d", rec.Code)
	}
	// dispatch 는 goroutine — 잠깐 폴링
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		ok := dispatched
		mu.Unlock()
		if ok {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("dispatch 가 호출되지 않음")
}

func TestHandler_서명불량_401(t *testing.T) {
	s := &server{signingSecret: testSecret, dispatcher: newDispatchClient("http://x", "o/r", "t")}
	s.now = func() time.Time { return time.Unix(1600000000, 0) }
	body := sampleBody()
	req := newSignedRequest("1600000000", body, "v0=bad")
	rec := httptest.NewRecorder()
	s.handleAction(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d (401 기대)", rec.Code)
	}
}

func newSignedRequest(ts string, body []byte, sig string) *http.Request {
	req := httptest.NewRequest(http.MethodPost, "/slack/incident-actions", bytesReader(body))
	req.Header.Set("X-Slack-Request-Timestamp", ts)
	req.Header.Set("X-Slack-Signature", sig)
	return req
}
