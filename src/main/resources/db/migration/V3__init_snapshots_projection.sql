-- V3: snapshots and read-model projection
CREATE TABLE snapshots (
    session_id       BIGINT NOT NULL,
    version          INT NOT NULL,
    last_event_id    BIGINT NOT NULL,
    last_sequence    BIGINT NOT NULL,
    state_json       JSON NOT NULL,
    created_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (session_id, version),
    INDEX idx_session_last_seq (session_id, last_sequence DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE session_projection (
    session_id              BIGINT PRIMARY KEY,
    participant_count       INT NOT NULL DEFAULT 0,
    message_count           BIGINT NOT NULL DEFAULT 0,
    last_message_at         DATETIME(3) NULL,
    last_applied_event_id   BIGINT NOT NULL DEFAULT 0,
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_last_message_at (last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
