package com.example.chat.projection.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.TreeMap;

// 스냅샷 페이로드. 동일한 이벤트 스트림을 재생했을 때 byte-identical stateJson을 얻기 위해
// JSON 직렬화 순서를 결정론적으로 유지한다.
@Getter
public class SessionState {

    // JVM 재시작 후에도 userId 기준 정렬이 유지되도록 TreeMap 사용.
    @JsonDeserialize(as = TreeMap.class)
    private final TreeMap<String, ParticipantState> participants;

    // sequence 순 적용 순서와 일치하는 메시지 삽입 순서를 보존하기 위해 LinkedHashMap 사용.
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
