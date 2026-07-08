package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"time"
)

// verifySlackSignature 는 Slack 서명(v0)과 타임스탬프 신선도(±5분)를 검증한다.
// body 는 반드시 파싱 전의 원본 바이트여야 한다.
func verifySlackSignature(signingSecret, timestamp, signature string, body []byte, now time.Time) error {
	ts, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		return fmt.Errorf("타임스탬프 파싱 실패: %w", err)
	}
	if delta := now.Unix() - ts; delta > 300 || delta < -300 {
		return fmt.Errorf("타임스탬프 만료(delta=%ds)", delta)
	}
	mac := hmac.New(sha256.New, []byte(signingSecret))
	mac.Write([]byte("v0:" + timestamp + ":" + string(body)))
	expected := "v0=" + hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(expected), []byte(signature)) {
		return fmt.Errorf("서명 불일치")
	}
	return nil
}
