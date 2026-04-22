package com.example.chat.projection.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.TreeMap;

// Snapshot payload. TreeMap and LinkedHashMap are used so JSON serialization order is deterministic,
// which lets us replay the same event stream and get byte-identical stateJson for snapshot regression tests.
@Getter
public class SessionState {

    // TreeMap keeps participants sorted by userId across JVM restarts.
    @JsonDeserialize(as = TreeMap.class)
    private final TreeMap<String, ParticipantState> participants;

    // LinkedHashMap preserves message insertion order (which matches sequence-sorted apply order).
    @JsonDeserialize(as = LinkedHashMap.class)
    private final LinkedHashMap<Long, MessageState> messages;

    private Long lastSequence;
    private Long lastAppliedEventId;

    public SessionState() {
        this.participants = new TreeMap<>();
        this.messages = new LinkedHashMap<>();
        this.lastSequence = 0L;
        this.lastAppliedEventId = 0L;
    }

    @JsonCreator
    public SessionState(@JsonProperty("participants") TreeMap<String, ParticipantState> participants,
                        @JsonProperty("messages") LinkedHashMap<Long, MessageState> messages,
                        @JsonProperty("lastSequence") Long lastSequence,
                        @JsonProperty("lastAppliedEventId") Long lastAppliedEventId) {
        this.participants = participants == null ? new TreeMap<>() : participants;
        this.messages = messages == null ? new LinkedHashMap<>() : messages;
        this.lastSequence = lastSequence == null ? 0L : lastSequence;
        this.lastAppliedEventId = lastAppliedEventId == null ? 0L : lastAppliedEventId;
    }

    public void setLastSequence(Long lastSequence) {
        this.lastSequence = lastSequence;
    }

    public void setLastAppliedEventId(Long lastAppliedEventId) {
        this.lastAppliedEventId = lastAppliedEventId;
    }

    public record ParticipantState(String userId, LocalDateTime joinedAt, String presence) {
        public static final String ONLINE = "ONLINE";
        public static final String OFFLINE = "OFFLINE";
    }

    public record MessageState(Long eventId, Long sequence, String userId, String text,
                               String status, LocalDateTime serverReceivedAt) {
        public static final String SENT = "SENT";
        public static final String EDITED = "EDITED";
        public static final String DELETED = "DELETED";
    }
}
