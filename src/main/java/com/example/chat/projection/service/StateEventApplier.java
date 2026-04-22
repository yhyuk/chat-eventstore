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

// Pure-function event replayer shared by SnapshotService (state building) and D5 restore API.
// Must stay deterministic: no System.currentTimeMillis(), no random, no external IO.
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
            // Replay order anomaly -- ignore; DISCONNECT before JOIN should never happen in normal flow.
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
