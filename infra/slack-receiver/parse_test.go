package main

import (
	"net/url"
	"testing"
)

const sampleMessageText = ":rotating_light: *readum ERROR 발생*\n" +
	"*env*: `dev`\n" +
	"*logger*: `com.readum.Foo`\n" +
	"*fingerprint*: `NullPointerException @ Foo.bar`\n" +
	"*deploy*: `6119aa2`\n" +
	"*traceId*: `abc123`\n" +
	"*spanId*: `-`\n" +
	"*time*: `2026-07-08T10:36:01Z`\n\n" +
	"*message*\n```에러 메시지```\n\n" +
	"*stacktrace*\n```java.lang.NullPointerException\n\tat Foo.bar(Foo.java:1)```\n"

func sampleBody() []byte {
	payload := `{"type":"block_actions","response_url":"https://hooks.slack/x","channel":{"id":"C1"},"message":{"ts":"111.222","text":` +
		mustJSONString(sampleMessageText) + `},"actions":[{"action_id":"create_incident_issue"}]}`
	return []byte("payload=" + url.QueryEscape(payload))
}

func TestParse_필드추출(t *testing.T) {
	si, inc, err := parseInteraction(sampleBody())
	if err != nil {
		t.Fatalf("파싱 실패: %v", err)
	}
	if si.Message.TS != "111.222" {
		t.Errorf("ts=%q", si.Message.TS)
	}
	if inc.Fingerprint != "NullPointerException @ Foo.bar" {
		t.Errorf("fingerprint=%q", inc.Fingerprint)
	}
	if inc.DeploySha != "6119aa2" {
		t.Errorf("deploy=%q", inc.DeploySha)
	}
	if inc.TraceID != "abc123" {
		t.Errorf("traceId=%q", inc.TraceID)
	}
	if inc.Message != "에러 메시지" {
		t.Errorf("message=%q", inc.Message)
	}
	if inc.Stacktrace != "java.lang.NullPointerException\n\tat Foo.bar(Foo.java:1)" {
		t.Errorf("stacktrace=%q", inc.Stacktrace)
	}
}

func TestParse_payload누락_에러(t *testing.T) {
	if _, _, err := parseInteraction([]byte("foo=bar")); err == nil {
		t.Fatal("payload 없는데 에러 안 남")
	}
}
