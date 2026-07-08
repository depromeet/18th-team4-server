package main

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"time"
)

type server struct {
	signingSecret string
	dispatcher    *dispatchClient
	now           func() time.Time // 테스트 주입용, 기본 time.Now
}

func (s *server) clock() time.Time {
	if s.now != nil {
		return s.now()
	}
	return time.Now()
}

func (s *server) handleAction(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}
	ts := r.Header.Get("X-Slack-Request-Timestamp")
	sig := r.Header.Get("X-Slack-Signature")
	if err := verifySlackSignature(s.signingSecret, ts, sig, body, s.clock()); err != nil {
		log.Printf("서명 검증 실패: %v", err)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	_, inc, err := parseInteraction(body)
	if err != nil {
		log.Printf("payload 파싱 실패: %v", err)
		http.Error(w, "bad payload", http.StatusBadRequest)
		return
	}
	// Slack 3초 규칙: 즉시 200 ack, 실제 dispatch 는 비동기로.
	w.WriteHeader(http.StatusOK)
	go func() {
		if err := s.dispatcher.dispatch(inc); err != nil {
			log.Printf("repository_dispatch 실패 fingerprint=%s: %v", inc.Fingerprint, err)
			return
		}
		log.Printf("분석 요청 발동 fingerprint=%s deploy=%s", inc.Fingerprint, inc.DeploySha)
	}()
}

func bytesReader(b []byte) io.Reader { return bytes.NewReader(b) }
