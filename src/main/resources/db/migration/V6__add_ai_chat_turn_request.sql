-- V6: 채팅 한 턴의 요청 기록 — 클라이언트 requestId 의 멱등 판정과 예약 복구의 정본.
-- (user_id, request_id) UNIQUE 가 재전송 중복을 DB 에서 원자적으로 막는다. 조회 후 삽입이 아니라
-- 삽입 시 유일 위반으로 판정하므로, 같은 ID 가 동시에 들어와도 정확히 한 건만 통과한다.
-- request_id 는 utf8mb4_bin — 멱등 키는 대소문자를 구분해 정확히 같은 문자열만 같은 요청으로 본다
-- (기본 utf8mb4_0900_ai_ci 는 대소문자를 무시해 다른 ID 를 중복으로 오판할 수 있다).
-- reserved_tokens / budget_period_key 는 예약 커밋 전(ACCEPTED)에는 비어 있고,
-- 예산 예약과 같은 트랜잭션에서 채워진다 — 복구가 무엇을 얼마나 되돌릴지 이 두 값으로 정한다.
-- expires_at 은 만료 복구(별도 작업)의 판정 기준 시각이다.
CREATE TABLE ai_chat_turn_request (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    session_id           BIGINT       NOT NULL,
    request_id           VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    budget_period_key    INT          NULL,
    reserved_tokens      INT          NULL,
    assistant_message_id BIGINT       NULL,
    failure_code         VARCHAR(50)  NULL,
    expires_at           DATETIME(6)  NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_chat_turn_request_user_request UNIQUE (user_id, request_id),
    INDEX idx_ai_chat_turn_request_recovery (status, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
