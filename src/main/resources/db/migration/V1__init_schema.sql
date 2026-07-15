CREATE TABLE notifications
(
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    idempotency_key        VARCHAR(200)  NOT NULL,
    receiver_id            VARCHAR(50)   NOT NULL,
    type                   VARCHAR(50)   NOT NULL,
    channel                VARCHAR(20)   NOT NULL,
    ref_type               VARCHAR(50)   NULL,
    ref_id                 VARCHAR(100)  NULL,
    payload                JSON          NULL,
    status                 VARCHAR(20)   NOT NULL,
    attempt_count          INT           NOT NULL DEFAULT 0,
    max_attempts           INT           NOT NULL,
    next_attempt_at        DATETIME(6)   NOT NULL,
    processing_started_at  DATETIME(6)   NULL,
    last_error             VARCHAR(1000) NULL,
    sent_at                DATETIME(6)   NULL,
    read_at                DATETIME(6)   NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,
    CONSTRAINT uk_notifications_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_notifications_status_next_attempt_at (status, next_attempt_at),
    INDEX idx_notifications_receiver_created (receiver_id, created_at)
);

CREATE TABLE notification_attempts
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    notification_id  BIGINT        NOT NULL,
    attempt_no       INT           NOT NULL,
    started_at       DATETIME(6)   NOT NULL,
    finished_at      DATETIME(6)   NULL,
    success          BOOLEAN       NOT NULL,
    error_message    VARCHAR(1000) NULL,
    CONSTRAINT fk_notification_attempts_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (id),
    CONSTRAINT uk_notification_attempts_no UNIQUE (notification_id, attempt_no),
    INDEX idx_notification_attempts_notification (notification_id)
);
