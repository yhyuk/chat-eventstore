package com.example.chat.projection.worker;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.domain.ProjectionStatus;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.projection.domain.DeadLetterEvent;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.repository.DeadLetterEventRepository;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.service.ProjectionService;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// Pin max-retry to 1 so DLQ promotion happens on a single drain, keeping the spy lifecycle simple.
@TestPropertySource(properties = "app.outbox.max-retry=1")
class OutboxPollerIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired private OutboxPoller poller;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventAppendService eventAppendService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionProjectionRepository projectionRepository;
    @Autowired private DeadLetterEventRepository dlqRepository;

    @SpyBean private ProjectionService projectionService;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        Mockito.reset(projectionService);
        dlqRepository.deleteAll();
        eventRepository.deleteAllInBatch();
        projectionRepository.deleteAll();
        sessionRepository.deleteAllInBatch();
        sessionId = sessionRepository.saveAndFlush(Session.create()).getId();
    }

    @Test
    void drain_marks_pending_events_done_and_updates_projection() {
        appendMessage(1L, "c1");
        appendMessage(2L, "c2");

        poller.drain();

        List<Event> events = eventRepository.findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(sessionId, 0L);
        assertThat(events).hasSize(2);
        assertThat(events).allMatch(e -> e.getProjectionStatus() == ProjectionStatus.DONE);

        Optional<SessionProjection> projection = projectionRepository.findById(sessionId);
        assertThat(projection).isPresent();
        assertThat(projection.get().getMessageCount()).isEqualTo(2L);
    }

    @Test
    void drain_increments_retry_count_on_apply_failure() {
        // Increment-only behaviour is covered by the unit test OutboxPollerTest.drain_increments_retry_count...
        // Here we just verify the retryCount is persisted alongside the lastError on the first failed attempt.
        appendMessage(1L, "c1");
        Mockito.doThrow(new RuntimeException("fail-1")).when(projectionService).apply(Mockito.any());

        poller.drain();

        Event event = eventRepository.findBySessionIdAndSequence(sessionId, 1L).orElseThrow();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("fail-1");
    }

    @Test
    void drain_moves_event_to_dlq_after_max_retries() {
        appendMessage(1L, "c1");
        Mockito.doThrow(new RuntimeException("always-fail")).when(projectionService).apply(Mockito.any());

        // Test profile pins max-retry to 1 so a single drain is enough to escalate to DLQ.
        poller.drain();

        Event event = eventRepository.findBySessionIdAndSequence(sessionId, 1L).orElseThrow();
        assertThat(event.getProjectionStatus()).isEqualTo(ProjectionStatus.FAILED);

        List<DeadLetterEvent> dlqRows = dlqRepository.findAllByOrderByMovedAtDesc();
        assertThat(dlqRows).hasSize(1);
        DeadLetterEvent dlq = dlqRows.get(0);
        // sequence column introduced in V6 must be populated.
        assertThat(dlq.getSequence()).isEqualTo(1L);
        assertThat(dlq.getOriginalEventId()).isNotNull();
    }

    @Test
    void drain_does_not_pick_up_failed_events() {
        appendMessage(1L, "c1");
        // FAILED 상태로 직접 마킹: 이후 drain에서 DLQ 재시도 전까지 무시되어야 한다.
        tx.executeWithoutResult(status ->
                eventRepository.updateProjectionStatus(
                        sessionId, 1L, ProjectionStatus.FAILED, 5, LocalDateTime.now(), "manual"));

        poller.drain();

        Mockito.verify(projectionService, Mockito.never()).apply(Mockito.any());
    }

    private void appendMessage(long sequence, String clientEventId) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId,
                "alice",
                sequence,
                EventType.MESSAGE,
                Map.of("text", "hi-" + sequence),
                LocalDateTime.now()
        );
        AppendResult r = eventAppendService.append(sessionId, req);
        assertThat(r.status()).isEqualTo(AppendResult.Status.ACCEPTED);
    }
}
