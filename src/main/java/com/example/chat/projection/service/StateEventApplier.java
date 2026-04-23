package com.example.chat.projection.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.projection.dto.SessionState;
import com.example.chat.projection.dto.SessionState.MessageState;
import com.example.chat.projection.dto.SessionState.ParticipantState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 이벤트를 재적용하여 세션 상태를 구성하는 순수 함수 리플레이어.
 *
 * <p>SnapshotService(상태 구성)와 D5 복원 API가 공유한다.
 * 결정론(determinism)을 유지해야 하므로 다음을 절대 사용하지 말 것:
 * System.currentTimeMillis(), 난수, 외부 IO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateEventApplier {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public SessionState apply(SessionState state, Event event) {
        Map<String, Object> payload = parsePayload(event.getPayload());
        EventType type = event.getType();
        switch (type) {
            case JOIN -> state.getParticipants().put(
                    event.getUserId(),
                    new ParticipantState(event.getUserId(), event.getClientTimestamp(), ParticipantState.ONLINE));
            case LEAVE -> state.getParticipants().remove(event.getUserId());
            case DISCONNECT -> updatePresence(state, event.getUserId(), ParticipantState.OFFLINE);
            case RECONNECT -> updatePresence(state, event.getUserId(), ParticipantState.ONLINE);
            case MESSAGE -> state.getMessages().put(
                    event.getSequence(),
                    new MessageState(
                            event.getId(),
                            event.getSequence(),
                            event.getUserId(),
                            stringOf(payload, "text"),
                            MessageState.SENT,
                            event.getServerReceivedAt()));
            case EDIT -> applyEdit(state, event, payload);
            case DELETE -> applyDelete(state, event, payload);
        }
        state.setLastSequence(Math.max(state.getLastSequence(), event.getSequence()));
        if (event.getId() != null) {
            state.setLastAppliedEventId(Math.max(state.getLastAppliedEventId(), event.getId()));
        }
        return state;
    }

    private void updatePresence(SessionState state, String userId, String presence) {
        ParticipantState current = state.getParticipants().get(userId);
        if (current == null) {
            // 정상 흐름에서는 JOIN 이전에 DISCONNECT가 올 수 없음. 재생 순서 이상이므로 무시.
            return;
        }
        state.getParticipants().put(userId, new ParticipantState(current.userId(), current.joinedAt(), presence));
    }

    private void applyEdit(SessionState state, Event event, Map<String, Object> payload) {
        Long target = longOf(payload, "targetEventId");
        if (target == null) {
            log.warn("EDIT event {} missing targetEventId", event.getId());
            return;
        }
        MessageState original = state.getMessages().get(target);
        if (original == null) {
            log.warn("EDIT target {} not found for event {}", target, event.getId());
            return;
        }
        String newText = stringOf(payload, "newText");
        state.getMessages().put(target, new MessageState(
                original.eventId(), original.sequence(), original.userId(),
                newText != null ? newText : original.text(),
                MessageState.EDITED,
                original.serverReceivedAt()));
    }

    private void applyDelete(SessionState state, Event event, Map<String, Object> payload) {
        Long target = longOf(payload, "targetEventId");
        if (target == null) {
            log.warn("DELETE event {} missing targetEventId", event.getId());
            return;
        }
        MessageState original = state.getMessages().get(target);
        if (original == null) {
            log.warn("DELETE target {} not found for event {}", target, event.getId());
            return;
        }
        state.getMessages().put(target, new MessageState(
                original.eventId(), original.sequence(), original.userId(),
                original.text(), MessageState.DELETED, original.serverReceivedAt()));
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, PAYLOAD_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse event payload: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String stringOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static Long longOf(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
