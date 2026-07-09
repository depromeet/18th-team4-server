package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
)

// Incident 는 Slack 메시지에서 뽑아 GitHub 로 넘길 장애 정보다.
type Incident struct {
	Fingerprint string
	DeploySha   string
	TraceID     string
	Message     string
	Stacktrace  string
}

type slackAction struct {
	ActionID string `json:"action_id"`
	Value    string `json:"value"`
}

type slackInteraction struct {
	Type    string `json:"type"`
	Message struct {
		Text string `json:"text"`
		TS   string `json:"ts"`
	} `json:"message"`
	Channel struct {
		ID string `json:"id"`
	} `json:"channel"`
	ResponseURL string        `json:"response_url"`
	Actions     []slackAction `json:"actions"`
}

// incidentValue 는 "분석 이슈 만들기" 버튼의 value 에 실린 구조화 장애 정보다.
// 앱(SlackWebhookAppender)이 버튼을 만들 때 이 JSON 을 value 로 넣는다.
type incidentValue struct {
	Fingerprint string `json:"fingerprint"`
	Deploy      string `json:"deploy"`
	TraceID     string `json:"traceId"`
	Message     string `json:"message"`
	Stacktrace  string `json:"stacktrace"`
}

var (
	reFingerprint = regexp.MustCompile("(?m)^\\*fingerprint\\*: `(.+?)`$")
	reDeploy      = regexp.MustCompile("(?m)^\\*deploy\\*: `(.+?)`$")
	reTrace       = regexp.MustCompile("(?m)^\\*traceId\\*: `(.+?)`$")
	reMessage     = regexp.MustCompile("(?s)\\*message\\*\\n```(.*?)```")
	reStack       = regexp.MustCompile("(?s)\\*stacktrace\\*\\n```(.*?)```")
)

func parseInteraction(body []byte) (*slackInteraction, *Incident, error) {
	values, err := url.ParseQuery(string(body))
	if err != nil {
		return nil, nil, fmt.Errorf("폼 파싱 실패: %w", err)
	}
	raw := values.Get("payload")
	if raw == "" {
		return nil, nil, fmt.Errorf("payload 없음")
	}
	var si slackInteraction
	if err := json.Unmarshal([]byte(raw), &si); err != nil {
		return nil, nil, fmt.Errorf("payload 언마샬 실패: %w", err)
	}
	// 우선 버튼 value(구조화 JSON)에서 뽑는다. Slack 은 클릭된 버튼의 value 를 항상 실어 보내므로
	// message.text 스크래핑(본문 포맷에 의존하는 취약한 방식)보다 견고하다.
	// value 가 없거나(옛 메시지) 비면 본문 스크래핑으로 폴백한다.
	inc := incidentFromActionValue(si.Actions)
	if inc == nil {
		inc = extractIncident(si.Message.Text)
	}
	return &si, inc, nil
}

// incidentFromActionValue 는 클릭된 "분석 이슈 만들기" 버튼의 value 에서 장애 정보를 읽는다.
//   - value 가 구조화 JSON 이면 그대로 매핑한다.
//   - value 가 JSON 이 아닌 문자열(옛 포맷 = fingerprint 만 실었던 메시지)이면 fingerprint 로 간주한다.
//   - 해당 버튼이 없거나 value 가 비어 있으면 nil 을 돌려 본문 스크래핑 폴백에 맡긴다.
func incidentFromActionValue(actions []slackAction) *Incident {
	for _, action := range actions {
		if action.ActionID != createIssueActionID || action.Value == "" {
			continue
		}
		var v incidentValue
		if err := json.Unmarshal([]byte(action.Value), &v); err == nil && (v.Fingerprint != "" || v.Deploy != "") {
			return &Incident{
				Fingerprint: v.Fingerprint,
				DeploySha:   v.Deploy,
				TraceID:     v.TraceID,
				Message:     v.Message,
				Stacktrace:  v.Stacktrace,
			}
		}
		return &Incident{Fingerprint: action.Value}
	}
	return nil
}

func extractIncident(text string) *Incident {
	first := func(re *regexp.Regexp) string {
		if m := re.FindStringSubmatch(text); m != nil {
			return m[1]
		}
		return ""
	}
	return &Incident{
		Fingerprint: first(reFingerprint),
		DeploySha:   first(reDeploy),
		TraceID:     first(reTrace),
		Message:     first(reMessage),
		Stacktrace:  first(reStack),
	}
}
