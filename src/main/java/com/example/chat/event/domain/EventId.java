package com.example.chat.event.domain;

import java.io.Serializable;
import java.util.Objects;

// Lombok 미적용: QueryDSL APT 처리 순서 충돌로 인해 equals/hashCode를 직접 구현한다.
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
