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

type slackInteraction struct {
	Type    string `json:"type"`
	Message struct {
		Text string `json:"text"`
		TS   string `json:"ts"`
	} `json:"message"`
	Channel struct {
		ID string `json:"id"`
	} `json:"channel"`
	ResponseURL string `json:"response_url"`
	Actions     []struct {
		ActionID string `json:"action_id"`
	} `json:"actions"`
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
	return &si, extractIncident(si.Message.Text), nil
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
