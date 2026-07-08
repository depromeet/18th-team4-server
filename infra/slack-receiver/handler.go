package main

import (
	"io"
	"log"
	"net/http"
	"time"
)

const createIssueActionID = "create_incident_issue"

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
	si, inc, err := parseInteraction(body)
	if err != nil {
		log.Printf("payload 파싱 실패: %v", err)
		http.Error(w, "bad payload", http.StatusBadRequest)
		return
	}
	// Slack 3초 규칙: 즉시 200 ack, 실제 dispatch 는 비동기로.
	w.WriteHeader(http.StatusOK)

	if !hasIncidentAction(si) {
		log.Printf("장애 이슈 생성 버튼이 아니라 dispatch 건너뜀")
		return
	}
	if inc.Fingerprint == "" && inc.DeploySha == "" {
		log.Printf("fingerprint·deploy 둘 다 비어 있어 dispatch 건너뜀")
		return
	}

	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("dispatch goroutine panic 복구: %v", r)
			}
		}()
		if err := s.dispatcher.dispatch(inc); err != nil {
			log.Printf("repository_dispatch 실패 fingerprint=%s: %v", inc.Fingerprint, err)
			return
		}
		log.Printf("분석 요청 발동 fingerprint=%s deploy=%s", inc.Fingerprint, inc.DeploySha)
	}()
}

// hasIncidentAction 은 상호작용에 장애 이슈 생성 버튼 클릭이 포함됐는지 확인한다.
func hasIncidentAction(si *slackInteraction) bool {
	for _, action := range si.Actions {
		if action.ActionID == createIssueActionID {
			return true
		}
	}
	return false
}
