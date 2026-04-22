-- V4: dead letter queue for exhausted retries
CREATE TABLE dead_letter_events (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_event_id   BIGINT NOT NULL,
    session_id          BIGINT NOT NULL,
    event_type          VARCHAR(20) NOT NULL,
    payload             JSON NOT NULL,
    error_message       VARCHAR(1024) NOT NULL,
    stack_trace         TEXT NULL,
    retry_count         INT NOT NULL,
    moved_at            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_session_moved (session_id, moved_at),
    INDEX idx_moved (moved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
