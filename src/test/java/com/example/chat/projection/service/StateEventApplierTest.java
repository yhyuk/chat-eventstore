package com.example.chat.projection.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.projection.config.SnapshotObjectMapperConfig;
import com.example.chat.projection.dto.SessionState;
import com.example.chat.projection.dto.SessionState.MessageState;
import com.example.chat.projection.dto.SessionState.ParticipantState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class StateEventApplierTest {

    private StateEventApplier applier;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new SnapshotObjectMapperConfig().snapshotObjectMapper();
        applier = new StateEventApplier(objectMapper);
    }

    @Test
    void join_adds_participant_with_online_presence() {
        SessionState state = new SessionState();
        Event e = event(1L, EventType.JOIN, "alice", "{}", 10L);

        applier.apply(state, e);

        assertThat(state.getParticipants()).containsKey("alice");
        assertThat(state.getParticipants().get("alice").presence()).isEqualTo(ParticipantState.ONLINE);
        assertThat(state.getLastSequence()).isEqualTo(10L);
    }

    @Test
    void leave_removes_participant() {
        SessionState state = new SessionState();
        applier.apply(state, event(1L, EventType.JOIN, "alice", "{}", 1L));
        applier.apply(state, event(2L, EventType.LEAVE, "alice", "{}", 2L));

        assertThat(state.getParticipants()).doesNotContainKey("alice");
    }

    @Test
    void disconnect_and_reconnect_toggle_presence() {
        SessionState state = new SessionState();
        applier.apply(state, event(1L, EventType.JOIN, "alice", "{}", 1L));
        applier.apply(state, event(2L, EventType.DISCONNECT, "alice", "{}", 2L));
        assertThat(state.getParticipants().get("alice").presence()).isEqualTo(ParticipantState.OFFLINE);

        applier.apply(state, event(3L, EventType.RECONNECT, "alice", "{}", 3L));
        assertThat(state.getParticipants().get("alice").presence()).isEqualTo(ParticipantState.ONLINE);
    }

    @Test
    void message_adds_entry_with_sent_status() {
        SessionState state = new SessionState();
        Event e = event(1L, EventType.MESSAGE, "alice", "{\"text\":\"hi\"}", 5L);

        applier.apply(state, e);

        MessageState msg = state.getMessages().get(5L);
        assertThat(msg).isNotNull();
        assertThat(msg.text()).isEqualTo("hi");
        assertThat(msg.status()).isEqualTo(MessageState.SENT);
    }

    @Test
    void edit_updates_target_message_text_and_status() {
        SessionState state = new SessionState();
        applier.apply(state, event(1L, EventType.MESSAGE, "alice", "{\"text\":\"original\"}", 1L));
        applier.apply(state, event(2L, EventType.EDIT, "alice",
                "{\"targetEventId\":1,\"newText\":\"edited text\"}", 2L));

        MessageState msg = state.getMessages().get(1L);
        assertThat(msg.text()).isEqualTo("edited text");
        assertThat(msg.status()).isEqualTo(MessageState.EDITED);
    }

    @Test
    void delete_marks_target_message_as_deleted() {
        SessionState state = new SessionState();
        applier.apply(state, event(1L, EventType.MESSAGE, "alice", "{\"text\":\"hi\"}", 1L));
        applier.apply(state, event(2L, EventType.DELETE, "alice",
                "{\"targetEventId\":1,\"reason\":\"spam\"}", 2L));

        MessageState msg = state.getMessages().get(1L);
        assertThat(msg.status()).isEqualTo(MessageState.DELETED);
        // text retained (history preservation)
        assertThat(msg.text()).isEqualTo("hi");
    }

    @Test
    void edit_with_missing_target_is_ignored() {
        SessionState state = new SessionState();
        applier.apply(state, event(1L, EventType.EDIT, "alice",
                "{\"targetEventId\":999,\"newText\":\"x\"}", 1L));

        assertThat(state.getMessages()).isEmpty();
    }

    @Test
    void deterministic_serialization_produces_identical_json() throws Exception {
        SessionState a = new SessionState();
        SessionState b = new SessionState();
        // Apply the same event stream to two independent state objects.
        for (long i = 1; i <= 5; i++) {
            Event e = event(i, EventType.JOIN, "user-" + i, "{}", i);
            applier.apply(a, e);
            applier.apply(b, e);
        }
        String jsonA = objectMapper.writeValueAsString(a);
        String jsonB = objectMapper.writeValueAsString(b);
        assertThat(jsonA).isEqualTo(jsonB);
        assertThat(jsonA).contains("\"user-1\"");
    }

    @Test
    void round_trip_preserves_treemap_and_linkedhashmap_types() throws Exception {
        SessionState state = new SessionState();
        applier.apply(state, event(1L, EventType.JOIN, "charlie", "{}", 1L));
        applier.apply(state, event(2L, EventType.JOIN, "alice", "{}", 2L));
        applier.apply(state, event(3L, EventType.MESSAGE, "alice", "{\"text\":\"hi\"}", 3L));

        String json = objectMapper.writeValueAsString(state);
        SessionState restored = objectMapper.readValue(json, SessionState.class);

        assertThat(restored.getParticipants()).isInstanceOf(TreeMap.class);
        assertThat(restored.getMessages()).isInstanceOf(LinkedHashMap.class);
        // TreeMap enforces sorted keys even if JSON came back in any order.
        assertThat(restored.getParticipants().firstKey()).isEqualTo("alice");
    }

    private Event event(long id, EventType type, String userId, String payloadJson, long sequence) {
        Event e = Event.builder()
                .sessionId(1L)
                .sequence(sequence)
                .clientEventId("c-" + id)
                .userId(userId)
                .type(type)
                .payload(payloadJson)
                .clientTimestamp(LocalDateTime.now())
                .build();
        setField(e, "id", id);
        setField(e, "serverReceivedAt", LocalDateTime.now());
        return e;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
