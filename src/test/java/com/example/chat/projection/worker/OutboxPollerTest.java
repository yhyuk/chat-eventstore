package com.example.chat.projection.worker;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.domain.ProjectionStatus;
import com.example.chat.event.repository.EventIdProjection;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.domain.DeadLetterEvent;
import com.example.chat.common.metrics.ChatMetrics;
import com.example.chat.projection.repository.DeadLetterEventRepository;
import com.example.chat.projection.service.ProjectionService;
import com.example.chat.projection.service.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPollerTest {

    @Mock private EventRepository eventRepository;
    @Mock private DeadLetterEventRepository dlqRepository;
    @Mock private ProjectionService projectionService;
    @Mock private SnapshotService snapshotService;
    @Mock private PlatformTransactionManager txManager;
    @Mock private ChatMetrics chatMetrics;

    private OutboxPoller poller;

    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        poller = new OutboxPoller(eventRepository, dlqRepository, projectionService,
                snapshotService, txManager, chatMetrics, 100, 5, true);
    }

    @Test
    void drain_empty_batch_noop() {
        when(eventRepository.fetchPendingEventIds(anyInt())).thenReturn(List.of());

        poller.drain();

        verify(projectionService, never()).apply(any());
    }

    @Test
    void drain_applies_each_pending_event_and_marks_done() {
        EventIdProjection id = projection(1L, 1L);
        when(eventRepository.fetchPendingEventIds(anyInt())).thenReturn(List.of(id));
        Event event = event(10L, 1L, 1L, ProjectionStatus.PENDING, 0);
        when(eventRepository.findBySessionIdAndSequence(1L, 1L)).thenReturn(Optional.of(event));

        poller.drain();

        verify(projectionService).apply(event);
        verify(eventRepository).updateProjectionStatus(
                1L, 1L, ProjectionStatus.DONE, 0, event.getNextRetryAt(), null);
        verify(snapshotService).createSnapshotIfNeeded(event);
    }

    @Test
    void drain_skips_event_that_is_not_pending() {
        EventIdProjection id = projection(1L, 1L);
        when(eventRepository.fetchPendingEventIds(anyInt())).thenReturn(List.of(id));
        Event event = event(10L, 1L, 1L, ProjectionStatus.DONE, 0);
        when(eventRepository.findBySessionIdAndSequence(1L, 1L)).thenReturn(Optional.of(event));

        poller.drain();

        verify(projectionService, never()).apply(any());
    }

    @Test
    void drain_skips_failed_events_in_persistence_context() {
        EventIdProjection id = projection(1L, 1L);
        when(eventRepository.fetchPendingEventIds(anyInt())).thenReturn(List.of(id));
        Event event = event(10L, 1L, 1L, ProjectionStatus.FAILED, 0);
        when(eventRepository.findBySessionIdAndSequence(1L, 1L)).thenReturn(Optional.of(event));

        poller.drain();

        verify(projectionService, never()).apply(any());
    }

    @Test
    void drain_increments_retry_count_on_failure() {
        EventIdProjection id = projection(1L, 1L);
        when(eventRepository.fetchPendingEventIds(anyInt())).thenReturn(List.of(id));
        Event event = event(10L, 1L, 1L, ProjectionStatus.PENDING, 0);
        when(eventRepository.findBySessionIdAndSequence(1L, 1L)).thenReturn(Optional.of(event));
        doThrowWhenApply(new RuntimeException("boom"));

        poller.drain();

        ArgumentCaptor<Integer> retryCount = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<ProjectionStatus> statusCap = ArgumentCaptor.forClass(ProjectionStatus.class);
        verify(eventRepository).updateProjectionStatus(
                any(), any(), statusCap.capture(), retryCount.capture(), any(), any());
        assertThat(statusCap.getValue()).isEqualTo(ProjectionStatus.PENDING);
        assertThat(retryCount.getValue()).isEqualTo(1);
        verify(dlqRepository, never()).save(any());
    }

    @Test
    void drain_moves_to_dlq_when_max_retry_exceeded() {
        EventIdProjection id = projection(1L, 1L);
        when(eventRepository.fetchPendingEventIds(anyInt())).thenReturn(List.of(id));
        Event event = event(10L, 1L, 1L, ProjectionStatus.PENDING, 4); // next attempt hits max=5
        when(eventRepository.findBySessionIdAndSequence(1L, 1L)).thenReturn(Optional.of(event));
        doThrowWhenApply(new RuntimeException("boom"));

        poller.drain();

        ArgumentCaptor<DeadLetterEvent> dlqCap = ArgumentCaptor.forClass(DeadLetterEvent.class);
        verify(dlqRepository).save(dlqCap.capture());
        DeadLetterEvent dlq = dlqCap.getValue();
        assertThat(dlq.getSessionId()).isEqualTo(1L);
        assertThat(dlq.getSequence()).isEqualTo(1L);
        assertThat(dlq.getOriginalEventId()).isEqualTo(10L);
        assertThat(dlq.getRetryCount()).isEqualTo(5);
        verify(eventRepository).updateProjectionStatus(
                any(), any(), org.mockito.ArgumentMatchers.eq(ProjectionStatus.FAILED),
                any(), any(), any());
    }

    @Test
    void drain_logs_and_swallows_top_level_db_failure() {
        when(eventRepository.fetchPendingEventIds(anyInt()))
                .thenThrow(new RuntimeException("db down"));

        // Expect no exception propagation -- scheduler must keep firing next cycle.
        poller.drain();

        verify(projectionService, never()).apply(any());
    }

    private void doThrowWhenApply(RuntimeException ex) {
        org.mockito.Mockito.doThrow(ex).when(projectionService).apply(any());
    }

    private EventIdProjection projection(Long sessionId, Long sequence) {
        return new EventIdProjection() {
            @Override public Long getSessionId() { return sessionId; }
            @Override public Long getSequence() { return sequence; }
        };
    }

    private Event event(Long id, Long sessionId, Long sequence, ProjectionStatus status, int retryCount) {
        Event e = Event.builder()
                .sessionId(sessionId)
                .sequence(sequence)
                .clientEventId("c" + id)
                .userId("alice")
                .type(EventType.MESSAGE)
                .payload("{}")
                .clientTimestamp(LocalDateTime.now())
                .build();
        setField(e, "id", id);
        setField(e, "projectionStatus", status);
        setField(e, "retryCount", retryCount);
        setField(e, "nextRetryAt", LocalDateTime.now());
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
