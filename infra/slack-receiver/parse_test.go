package main

import (
	"encoding/json"
	"net/url"
	"testing"
)

// mustJSONString 는 테스트용으로 문자열을 JSON 리터럴(따옴표 포함)로 만든다.
func mustJSONString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

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

// sampleValueJSON 은 앱(SlackWebhookAppender)이 "분석 이슈 만들기" 버튼 value 에 싣는 구조화 JSON 이다.
const sampleValueJSON = `{"fingerprint":"NullPointerException @ Foo.bar","deploy":"6119aa2","traceId":"abc123","message":"에러 메시지","stacktrace":"java.lang.NullPointerException\n\tat Foo.bar(Foo.java:1)"}`

// sampleBody 는 버튼 value(구조화 JSON) + 메시지 본문을 모두 담은 실제 모양의 상호작용 폼 바디다.
func sampleBody() []byte {
	payload := `{"type":"block_actions","response_url":"https://hooks.slack/x","channel":{"id":"C1"},"message":{"ts":"111.222","text":` +
		mustJSONString(sampleMessageText) + `},"actions":[{"action_id":"create_incident_issue","value":` + mustJSONString(sampleValueJSON) + `}]}`
	return []byte("payload=" + url.QueryEscape(payload))
}

func TestParse_버튼value에서_필드추출(t *testing.T) {
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

// 이번 장애의 핵심 회귀: 실제 Slack payload 에서 message.text 가 비어 있어도(원인),
// 버튼 value 만으로 fingerprint·deploy 를 뽑아 dispatch 가 성립해야 한다.
func TestParse_본문이비어도_버튼value로_추출(t *testing.T) {
	payload := `{"type":"block_actions","message":{"ts":"1","text":""},"actions":[{"action_id":"create_incident_issue","value":` +
		mustJSONString(sampleValueJSON) + `}]}`
	body := []byte("payload=" + url.QueryEscape(payload))

	_, inc, err := parseInteraction(body)
	if err != nil {
		t.Fatalf("파싱 실패: %v", err)
	}
	if inc.Fingerprint != "NullPointerException @ Foo.bar" || inc.DeploySha != "6119aa2" {
		t.Fatalf("버튼 value 로 추출 실패: %+v", inc)
	}
}

// value 가 비면(옛 메시지 등) 메시지 본문 스크래핑으로 폴백한다.
func TestParse_value없으면_본문폴백(t *testing.T) {
	payload := `{"type":"block_actions","message":{"ts":"1","text":` +
		mustJSONString(sampleMessageText) + `},"actions":[{"action_id":"create_incident_issue","value":""}]}`
	body := []byte("payload=" + url.QueryEscape(payload))

	_, inc, err := parseInteraction(body)
	if err != nil {
		t.Fatalf("파싱 실패: %v", err)
	}
	if inc.Fingerprint != "NullPointerException @ Foo.bar" || inc.DeploySha != "6119aa2" {
		t.Fatalf("본문 폴백 추출 실패: %+v", inc)
	}
}

// 옛 포맷 호환: value 가 JSON 이 아니라 fingerprint 문자열이면 fingerprint 로만 채운다.
func TestParse_옛포맷_value는_fingerprint로(t *testing.T) {
	payload := `{"type":"block_actions","message":{"ts":"1","text":""},"actions":[{"action_id":"create_incident_issue","value":"SomeException @ Foo.bar"}]}`
	body := []byte("payload=" + url.QueryEscape(payload))

	_, inc, err := parseInteraction(body)
	if err != nil {
		t.Fatalf("파싱 실패: %v", err)
	}
	if inc.Fingerprint != "SomeException @ Foo.bar" {
		t.Fatalf("옛 포맷 fingerprint 추출 실패: %+v", inc)
	}
}

func TestParse_payload누락_에러(t *testing.T) {
	if _, _, err := parseInteraction([]byte("foo=bar")); err == nil {
		t.Fatal("payload 없는데 에러 안 남")
	}
}
