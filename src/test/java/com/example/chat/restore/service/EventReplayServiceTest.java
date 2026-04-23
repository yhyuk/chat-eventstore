package com.example.chat.restore.service;

import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.config.SnapshotObjectMapperConfig;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.dto.SessionState;
import com.example.chat.projection.dto.SessionState.MessageState;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.projection.service.StateEventApplier;
import com.example.chat.restore.dto.TimelineResponse;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventReplayServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private EventRepository eventRepository;
    @Mock private SnapshotRepository snapshotRepository;

    private EventReplayService service;
    private ObjectMapper objectMapper;

    private static final Long SESSION_ID = 42L;

    @BeforeEach
    void setUp() {
        objectMapper = new SnapshotObjectMapperConfig().snapshotObjectMapper();
        StateEventApplier applier = new StateEventApplier(objectMapper);
        service = new EventReplayService(sessionRepository, eventRepository, snapshotRepository,
                applier, objectMapper);
    }

    @Test
    void restoreAt_returns_404_when_session_missing() {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.restoreAt(SESSION_ID, LocalDateTime.now()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void restoreAt_returns_empty_when_no_events_at_or_before_at() {
        LocalDateTime at = LocalDateTime.now();
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(eventRepository.findMaxSequenceBySessionIdAndServerReceivedAtLessThanEqual(SESSION_ID, at))
                .thenReturn(Optional.empty());

        TimelineResponse r = service.restoreAt(SESSION_ID, at);

        assertThat(r.replayedEventCount()).isZero();
        assertThat(r.restoredFromSnapshot()).isFalse();
        assertThat(r.participants()).isEmpty();
        assertThat(r.messages()).isEmpty();
    }

    @Test
    void restoreAt_replays_all_events_when_no_snapshot_exists() {
        LocalDateTime t0 = LocalDateTime.now();
        LocalDateTime at = t0.plusSeconds(10);

        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(eventRepository.findMaxSequenceBySessionIdAndServerReceivedAtLessThanEqual(SESSION_ID, at))
                .thenReturn(Optional.of(2L));
        when(snapshotRepository.findTopBySessionIdAndLastSequenceLessThanEqualOrderByVersionDesc(SESSION_ID, 2L))
                .thenReturn(Optional.empty());

        List<Event> events = List.of(
                event(1L, EventType.JOIN, "alice", "{}", 1L, t0),
                event(2L, EventType.MESSAGE, "alice", "{\"text\":\"hi\"}", 2L, t0.plusSeconds(1)));
        when(eventRepository
                .findBySessionIdAndSequenceGreaterThanAndServerReceivedAtLessThanEqualOrderBySequenceAsc(
                        eq(SESSION_ID), eq(0L), eq(at)))
                .thenReturn(events);

        TimelineResponse r = service.restoreAt(SESSION_ID, at);

        assertThat(r.replayedEventCount()).isEqualTo(2);
        assertThat(r.restoredFromSnapshot()).isFalse();
        assertThat(r.snapshotVersion()).isNull();
        assertThat(r.participants()).hasSize(1);
        assertThat(r.messages()).hasSize(1);
    }

    @Test
    void restoreAt_uses_snapshot_and_replays_only_events_after_snapshot() throws Exception {
        LocalDateTime t0 = LocalDateTime.now();
        LocalDateTime at = t0.plusSeconds(10);

        // Build a snapshot state manually (alice joined at sequence 1, message at sequence 2).
        SessionState snapState = new SessionState();
        snapState.getParticipants().put("alice",
                new SessionState.ParticipantState("alice", t0, SessionState.ParticipantState.ONLINE));
        snapState.getMessages().put(2L,
                new MessageState(100L, 2L, "alice", "hi", MessageState.SENT, t0));
        snapState.setLastSequence(2L);
        snapState.setLastAppliedEventId(100L);
        String stateJson = objectMapper.writeValueAsString(snapState);

        Snapshot snapshot = Snapshot.builder()
                .sessionId(SESSION_ID).version(1).lastEventId(100L).lastSequence(2L)
                .stateJson(stateJson).createdAt(t0).build();

        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(eventRepository.findMaxSequenceBySessionIdAndServerReceivedAtLessThanEqual(SESSION_ID, at))
                .thenReturn(Optional.of(3L));
        when(snapshotRepository.findTopBySessionIdAndLastSequenceLessThanEqualOrderByVersionDesc(SESSION_ID, 3L))
                .thenReturn(Optional.of(snapshot));

        // Only the third event should be replayed on top of the snapshot.
        List<Event> replayed = List.of(
                event(101L, EventType.MESSAGE, "alice", "{\"text\":\"world\"}", 3L, t0.plusSeconds(5)));
        when(eventRepository
                .findBySessionIdAndSequenceGreaterThanAndServerReceivedAtLessThanEqualOrderBySequenceAsc(
                        eq(SESSION_ID), eq(2L), eq(at)))
                .thenReturn(replayed);

        TimelineResponse r = service.restoreAt(SESSION_ID, at);

        assertThat(r.restoredFromSnapshot()).isTrue();
        assertThat(r.snapshotVersion()).isEqualTo(1);
        assertThat(r.replayedEventCount()).isEqualTo(1);
        assertThat(r.messages()).hasSize(2);   // 2 from snapshot + 1 replayed, but msg-2 was already there
        // The replayed message 3 must be present.
        assertThat(r.messages()).extracting(MessageState::sequence).contains(3L);
    }

    @Test
    void restoreAt_with_null_at_substitutes_now() {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(eventRepository.findMaxSequenceBySessionIdAndServerReceivedAtLessThanEqual(eq(SESSION_ID), any()))
                .thenReturn(Optional.empty());

        TimelineResponse r = service.restoreAt(SESSION_ID, null);

        assertThat(r.at()).isNotNull();
    }

    private Event event(long id, EventType type, String userId, String payloadJson,
                        long sequence, LocalDateTime serverReceivedAt) {
        Event e = Event.builder()
                .sessionId(SESSION_ID)
                .sequence(sequence)
                .clientEventId("c-" + id)
                .userId(userId)
                .type(type)
                .payload(payloadJson)
                .clientTimestamp(serverReceivedAt)
                .build();
        setField(e, "id", id);
        setField(e, "serverReceivedAt", serverReceivedAt);
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
