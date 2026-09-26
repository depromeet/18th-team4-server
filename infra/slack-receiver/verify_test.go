package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"testing"
	"time"
)

const testSecret = "8f742231b10e8888abcd99yyyzzz85a5"

func sign(secret, ts string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte("v0:" + ts + ":" + string(body)))
	return "v0=" + hex.EncodeToString(mac.Sum(nil))
}

func TestVerify_정상서명_통과(t *testing.T) {
	now := time.Unix(1600000000, 0)
	ts := "1600000000"
	body := []byte("payload=%7B%7D")
	if err := verifySlackSignature(testSecret, ts, sign(testSecret, ts, body), body, now); err != nil {
		t.Fatalf("정상 서명인데 실패: %v", err)
	}
}

func TestVerify_위조서명_거부(t *testing.T) {
	now := time.Unix(1600000000, 0)
	ts := "1600000000"
	body := []byte("payload=%7B%7D")
	if err := verifySlackSignature(testSecret, ts, "v0=deadbeef", body, now); err == nil {
		t.Fatal("위조 서명인데 통과함")
	}
}

func TestVerify_만료타임스탬프_거부(t *testing.T) {
	now := time.Unix(1600000600, 0) // 10분 뒤
	ts := "1600000000"
	body := []byte("payload=%7B%7D")
	if err := verifySlackSignature(testSecret, ts, sign(testSecret, ts, body), body, now); err == nil {
		t.Fatal("만료 타임스탬프인데 통과함")
	}
}
