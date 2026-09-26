-- V4: 컨텍스트 요약 워커 핫패스용 복합 인덱스.
-- findCompletedMessagesAfter / sumRecentMessageTokens 는 (session_id = ? AND status = COMPLETED AND id > ?)
-- 로 거르고 id 로 정렬하는데, 기존 인덱스 (session_id, created_at, id) 는 status 를 못 타 세션 전체를 훑는다.
-- (session_id, status, id): 세션·상태 동등 매칭 + id 범위/정렬을 한 인덱스로 커버.
CREATE INDEX idx_ai_chat_message_session_status_id
    ON ai_chat_message (session_id, status, id);
