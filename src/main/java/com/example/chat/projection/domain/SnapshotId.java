package com.example.chat.projection.domain;

import java.io.Serializable;
import java.util.Objects;

// Excluded from Lombok due to QueryDSL APT ordering conflicts
public class SnapshotId implements Serializable {

    private Long sessionId;
    private Integer version;

    public SnapshotId() {
    }

    public SnapshotId(Long sessionId, Integer version) {
        this.sessionId = sessionId;
        this.version = version;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Integer getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SnapshotId)) return false;
        SnapshotId other = (SnapshotId) o;
        return Objects.equals(sessionId, other.sessionId)
                && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, version);
    }
}
