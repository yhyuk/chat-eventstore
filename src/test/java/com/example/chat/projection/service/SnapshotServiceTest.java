package com.example.chat.projection.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.config.SnapshotObjectMapperConfig;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnapshotServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private SnapshotRepository snapshotRepository;
    @Mock private SessionProjectionRepository projectionRepository;
    @Mock private SessionRepository sessionRepository;

    private SnapshotService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new SnapshotObjectMapperConfig().snapshotObjectMapper();
        StateEventApplier applier = new StateEventApplier(objectMapper);
        service = new SnapshotService(eventRepository, snapshotRepository, projectionRepository,
                sessionRepository, applier, objectMapper, 100);
    }

    @Test
    void createSnapshotIfNeeded_skip_when_threshold_not_reached() {
        Event event = messageEvent(1L, 50L);
        when(projectionRepository.findById(1L))
                .thenReturn(Optional.of(projection(1L, 50L, 42L)));

        service.createSnapshotIfNeeded(event);

        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void createSnapshotIfNeeded_triggers_snapshot_at_threshold() {
        Event event = messageEvent(1L, 100L);
        when(projectionRepository.findById(1L))
                .thenReturn(Optional.of(projection(1L, 100L, 100L)));
        when(snapshotRepository.findTopBySessionIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());
        when(eventRepository.findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(1L, 0L))
                .thenReturn(List.of(event));

        service.createSnapshotIfNeeded(event);

        ArgumentCaptor<Snapshot> snapCap = ArgumentCaptor.forClass(Snapshot.class);
        verify(snapshotRepository).save(snapCap.capture());
        assertThat(snapCap.getValue().getVersion()).isEqualTo(1);
        // Old snapshot cleanup uses Native DELETE, not a derived deleteBy...
        verify(snapshotRepository).deleteOldSnapshotsNative(eq(1L), any(Integer.class));
    }

    @Test
    void createFinalSnapshot_skips_when_session_already_captured() {
        Session session = mockSession(1L, 100L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        Snapshot latest = Snapshot.builder()
                .sessionId(1L).version(1).lastEventId(100L).lastSequence(100L)
                .stateJson("{}").createdAt(LocalDateTime.now()).build();
        when(snapshotRepository.findTopBySessionIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(latest));

        service.createFinalSnapshot(1L);

        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void createFinalSnapshot_creates_when_session_missing_snapshot() {
        Session session = mockSession(1L, 50L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(snapshotRepository.findTopBySessionIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty());
        when(eventRepository.findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(1L, 0L))
                .thenReturn(List.of(messageEvent(1L, 1L), messageEvent(2L, 2L)));

        service.createFinalSnapshot(1L);

        verify(snapshotRepository).save(any());
    }

    @Test
    void createSnapshot_uses_previous_state_and_only_new_events() {
        Snapshot previous = Snapshot.builder()
                .sessionId(1L).version(2).lastEventId(50L).lastSequence(50L)
                .stateJson("{\"participants\":{},\"messages\":{},\"lastSequence\":50,\"lastAppliedEventId\":50}")
                .createdAt(LocalDateTime.now()).build();
        when(snapshotRepository.findTopBySessionIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(previous));
        when(eventRepository.findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(1L, 50L))
                .thenReturn(List.of(messageEvent(51L, 51L)));

        service.createSnapshot(1L);

        ArgumentCaptor<Snapshot> snapCap = ArgumentCaptor.forClass(Snapshot.class);
        verify(snapshotRepository).save(snapCap.capture());
        assertThat(snapCap.getValue().getVersion()).isEqualTo(3);
    }

    private SessionProjection projection(Long sessionId, Long messageCount, Long lastAppliedEventId) {
        return SessionProjection.builder()
                .sessionId(sessionId)
                .participantCount(1)
                .messageCount(messageCount)
                .lastAppliedEventId(lastAppliedEventId)
                .build();
    }

    private Event messageEvent(long id, long sequence) {
        Event e = Event.builder()
                .sessionId(1L)
                .sequence(sequence)
                .clientEventId("c" + id)
                .userId("alice")
                .type(EventType.MESSAGE)
                .payload("{\"text\":\"hi\"}")
                .clientTimestamp(LocalDateTime.now())
                .build();
        setField(e, "id", id);
        setField(e, "serverReceivedAt", LocalDateTime.now());
        return e;
    }

    private Session mockSession(Long id, Long lastSequence) {
        Session session = Session.create();
        setField(session, "id", id);
        setField(session, "lastSequence", lastSequence);
        return session;
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
