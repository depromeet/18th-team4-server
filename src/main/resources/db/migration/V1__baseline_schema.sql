-- V1 베이스라인: 2026-07-06 기준 전체 스키마 (엔티티 어노테이션이 원본이었던 스키마를 SQL 로 옮김).
-- 이후 스키마 변경은 V2, V3 ... 마이그레이션 파일로만 한다.

CREATE TABLE `user` (
    id                         BIGINT       NOT NULL AUTO_INCREMENT,
    device_id                  VARCHAR(255) NULL,
    session_id                 VARCHAR(36)  NOT NULL,
    nickname                   VARCHAR(10)  NULL,
    last_selected_user_book_id BIGINT       NULL,
    onboarding_completed       BIT(1)       NOT NULL,
    created_at                 DATETIME(6)  NOT NULL,
    updated_at                 DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_device_id UNIQUE (device_id),
    CONSTRAINT uk_user_session_id UNIQUE (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE refresh_token (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    jwt_id           VARCHAR(64)  NOT NULL,
    parent_jwt_id    VARCHAR(64)  NULL,
    issued_at        TIMESTAMP(6) NOT NULL,
    expires_at       TIMESTAMP(6) NOT NULL,
    rotated_at       TIMESTAMP(6) NULL,
    grace_expires_at TIMESTAMP(6) NULL,
    revoked_at       TIMESTAMP(6) NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_jwt_id UNIQUE (jwt_id),
    CONSTRAINT uk_refresh_token_parent_jwt_id UNIQUE (parent_jwt_id),
    INDEX idx_refresh_token_user_revoked (user_id, revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_chat_session (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_book_id       BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    user_message_count INT          NOT NULL,
    accumulated_tokens INT          NOT NULL,
    title              VARCHAR(100) NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ai_chat_session_user_book (user_book_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE ai_chat_message (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    session_id    BIGINT      NOT NULL,
    role          VARCHAR(20) NOT NULL,
    content       TEXT        NOT NULL,
    quote_text    TEXT        NULL,
    input_tokens  INT         NULL,
    output_tokens INT         NULL,
    total_tokens  INT         NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ai_chat_message_session_created_id (session_id, created_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE user_book (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    book_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_book_user_book UNIQUE (user_id, book_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE book (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    external_id    VARCHAR(255)  NOT NULL,
    title          VARCHAR(500)  NOT NULL,
    authors        VARCHAR(500)  NULL,
    publisher      VARCHAR(255)  NULL,
    published_year INT           NULL,
    cover_url      VARCHAR(1000) NULL,
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_book_external_id UNIQUE (external_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE summary (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    user_book_id       BIGINT       NOT NULL,
    ai_chat_session_id BIGINT       NOT NULL,
    quote              TEXT         NULL,
    title              VARCHAR(500) NULL,
    body               TEXT         NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_summary_session UNIQUE (ai_chat_session_id),
    INDEX idx_summary_user_book (user_book_id),
    INDEX idx_summary_session (ai_chat_session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE summary_job (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    ai_chat_session_id BIGINT      NOT NULL,
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
    CONSTRAINT uk_summary_job_active_session UNIQUE (active_session_id),
    INDEX idx_summary_job_session (ai_chat_session_id, status),
    INDEX idx_summary_job_claim (status, next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
