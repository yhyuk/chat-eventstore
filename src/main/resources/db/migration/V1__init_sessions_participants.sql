-- V1: sessions and participants tables
CREATE TABLE sessions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at        DATETIME(3) NULL,
    last_sequence   BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,
    INDEX idx_status_created (status, created_at),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE participants (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    joined_at       DATETIME(3) NOT NULL,
    left_at         DATETIME(3) NULL,
    UNIQUE KEY uk_session_user (session_id, user_id),
    INDEX idx_session (session_id),
    CONSTRAINT fk_participants_session FOREIGN KEY (session_id) REFERENCES sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
