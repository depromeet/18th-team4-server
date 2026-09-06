-- V5: 사용자 토큰 예산을 Redis 카운터에서 DB 원장(일 단위·예약제)으로 전환.
-- user_token_budget: (user_id, period_key=KST 날짜) 당 1행. 행 생성은 원자 UPSERT,
-- 예약은 "used_tokens + 예약량 <= max_budget" 조건부 원자 UPDATE 로만 일어난다.
-- ai_chat_token_settlement: 생성 턴 1회의 정산 멱등 기록 — message_id UNIQUE 가 이중 정산을 차단한다.
CREATE TABLE user_token_budget (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    period_key  INT         NOT NULL,
    used_tokens BIGINT      NOT NULL,
    max_budget  BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_token_budget_user_period UNIQUE (user_id, period_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_chat_token_settlement (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL,
    message_id     BIGINT      NOT NULL,
    settled_tokens INT         NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_chat_token_settlement_message UNIQUE (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
