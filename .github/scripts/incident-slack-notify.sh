#!/usr/bin/env bash
# incident-analysis.yml 전용 Slack 게시 도우미.
#
# 사용: incident-slack-notify.sh <메시지> [thread_ts] [reply_broadcast(true|false)]
# 환경 변수: SLACK_BOT_TOKEN, SLACK_CHANNEL_ID (스레드 게시용, 선택),
#            SLACK_WEBHOOK_URL (채널 일반 메시지 fallback, 선택)
#
# Bot 토큰·채널 ID·thread_ts 가 모두 있으면 원본 에러 알림의 스레드에 게시하고,
# 하나라도 없거나 게시에 실패하면 Incoming Webhook 채널 메시지로 대체한다.
# 둘 다 없으면 알림만 생략한다 (분석 자체는 영향 없음).
set -euo pipefail

TEXT="$1"
THREAD_TS="${2:-}"
REPLY_BROADCAST="${3:-false}"

if [ -n "${SLACK_BOT_TOKEN:-}" ] && [ -n "${SLACK_CHANNEL_ID:-}" ] && [ -n "$THREAD_TS" ]; then
  RESPONSE=$(jq -n \
      --arg channel "$SLACK_CHANNEL_ID" \
      --arg thread_ts "$THREAD_TS" \
      --arg text "$TEXT" \
      --argjson reply_broadcast "$REPLY_BROADCAST" \
      '{channel: $channel, thread_ts: $thread_ts, reply_broadcast: $reply_broadcast, text: $text}' \
    | curl -sS -X POST \
        -H "Authorization: Bearer $SLACK_BOT_TOKEN" \
        -H 'Content-Type: application/json; charset=utf-8' \
        -d @- https://slack.com/api/chat.postMessage) || RESPONSE='{"ok":false,"error":"curl_failed"}'

  if [ "$(printf '%s' "$RESPONSE" | jq -r '.ok')" = "true" ]; then
    exit 0
  fi
  echo "스레드 게시 실패($(printf '%s' "$RESPONSE" | jq -r '.error // "unknown"')) — 채널 메시지로 대체한다"
fi

if [ -n "${SLACK_WEBHOOK_URL:-}" ]; then
  jq -n --arg text "$TEXT" '{text: $text}' \
    | curl -sS -X POST -H 'Content-Type: application/json' -d @- "$SLACK_WEBHOOK_URL"
else
  echo "SLACK_WEBHOOK_URL 미설정 — 알림을 생략한다"
fi
