-- V6: add sequence column to dead_letter_events so DLQ retry can look up the original event
-- via the composite key (session_id, sequence) instead of the surrogate id.
ALTER TABLE dead_letter_events
    ADD COLUMN sequence BIGINT NOT NULL DEFAULT 0 AFTER session_id;

CREATE INDEX idx_session_sequence ON dead_letter_events (session_id, sequence);
