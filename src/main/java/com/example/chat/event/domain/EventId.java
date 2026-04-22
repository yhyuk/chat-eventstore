package com.example.chat.event.domain;

import java.io.Serializable;
import java.util.Objects;

// Excluded from Lombok due to QueryDSL APT ordering conflicts
public class EventId implements Serializable {

    private Long sessionId;
    private Long sequence;

    public EventId() {
    }

    public EventId(Long sessionId, Long sequence) {
        this.sessionId = sessionId;
        this.sequence = sequence;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getSequence() {
        return sequence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventId)) return false;
        EventId other = (EventId) o;
        return Objects.equals(sessionId, other.sessionId)
                && Objects.equals(sequence, other.sequence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, sequence);
    }
}
