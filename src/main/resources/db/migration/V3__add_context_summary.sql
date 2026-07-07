-- PR-3: 채팅 컨텍스트 누적 요약 + 요약 작업 큐.
-- 요약은 원문(ai_chat_message)에서 언제든 재생성 가능한 파생 데이터다. 원문은 삭제하지 않는다.

-- 세션당 1행. 갱신되는 누적 요약. summarized_up_to_message_id 요약 반영 지점으로 요약 구간과 최근 원문 대화를 정확히 나눈다.
CREATE TABLE ai_chat_context_summary (
    id                          BIGINT      NOT NULL AUTO_INCREMENT,
    session_id                  BIGINT      NOT NULL,
    content                     TEXT        NOT NULL,
    summarized_up_to_message_id BIGINT      NOT NULL,
    version                     INT         NOT NULL,
    token_count                 INT         NOT NULL,
    created_at                  DATETIME(6) NOT NULL,
    updated_at                  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_chat_context_summary_session UNIQUE (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 요약 작업 큐. 큐 컬럼은 summary_job 패턴을 복제한다. active_session_id unique 로 세션당 활성 작업 1개.
CREATE TABLE ai_chat_context_summary_job (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    session_id         BIGINT      NOT NULL,
    active_session_id  BIGINT      NULL,
    status             VARCHAR(20) NOT NULL,
    lock_owner         VARCHAR(36) NULL,
    locked_until       DATETIME(6) NULL,
    attempt_count      INT         NOT NULL,
    next_attempt_at    DATETIME(6) NOT NULL,
    last_error_code    VARCHAR(50) NULL,
    last_error_message TEXT        NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_chat_context_summary_job_active_session UNIQUE (active_session_id),
    INDEX idx_ai_chat_context_summary_job_session (session_id, status),
    INDEX idx_ai_chat_context_summary_job_claim (status, next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
