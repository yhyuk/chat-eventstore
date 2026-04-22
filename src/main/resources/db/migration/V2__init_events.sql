-- V2: events (append-only) table
CREATE TABLE events (
    session_id          BIGINT NOT NULL,
    sequence            BIGINT NOT NULL,
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    client_event_id     VARCHAR(64) NOT NULL,
    user_id             VARCHAR(64) NOT NULL,
    type                VARCHAR(20) NOT NULL,
    payload             JSON NOT NULL,
    client_timestamp    DATETIME(3) NOT NULL,
    server_received_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    projection_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count         INT NOT NULL DEFAULT 0,
    next_retry_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error          VARCHAR(1024) NULL,
    PRIMARY KEY (session_id, sequence),
    UNIQUE KEY uk_id (id),
    UNIQUE KEY uk_session_client_event (session_id, client_event_id),
    INDEX idx_projection_status_retry (projection_status, next_retry_at),
    INDEX idx_session_received (session_id, server_received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
