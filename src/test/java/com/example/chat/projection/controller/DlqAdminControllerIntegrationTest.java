package com.example.chat.projection.controller;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.domain.ProjectionStatus;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.domain.DeadLetterEvent;
import com.example.chat.projection.repository.DeadLetterEventRepository;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.service.ProjectionService;
import com.example.chat.projection.worker.OutboxPoller;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@TestPropertySource(properties = "app.outbox.max-retry=1")
class DlqAdminControllerIntegrationTest extends AbstractFullIntegrationTest {

    @LocalServerPort private int port;

    @Autowired private EventAppendService eventAppendService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionProjectionRepository projectionRepository;
    @Autowired private DeadLetterEventRepository dlqRepository;
    @Autowired private OutboxPoller outboxPoller;

    @SpyBean private ProjectionService projectionService;
    @Autowired private PlatformTransactionManager transactionManager;

    private final RestTemplate rest = new RestTemplate();
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
    void list_returns_dlq_entries_with_sequence() {
        Long dlqId = forceOneIntoDlq();

        ResponseEntity<DlqEntry[]> response = rest.getForEntity(url(""), DlqEntry[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        DlqEntry entry = response.getBody()[0];
        assertThat(entry.id()).isEqualTo(dlqId);
        assertThat(entry.sessionId()).isEqualTo(sessionId);
        assertThat(entry.sequence()).isEqualTo(1L);
    }

    @Test
    void retry_resets_event_to_pending_and_removes_dlq_row() {
        Long dlqId = forceOneIntoDlq();

        ResponseEntity<Void> response = rest.postForEntity(url("/" + dlqId + "/retry"), null, Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Event event = eventRepository.findBySessionIdAndSequence(sessionId, 1L).orElseThrow();
        assertThat(event.getProjectionStatus()).isEqualTo(ProjectionStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(0);
        assertThat(dlqRepository.findById(dlqId)).isEmpty();
    }

    @Test
    void delete_removes_dlq_row_with_no_content() {
        Long dlqId = forceOneIntoDlq();

        ResponseEntity<Void> response = rest.exchange(
                url("/" + dlqId),
                org.springframework.http.HttpMethod.DELETE,
                null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dlqRepository.findById(dlqId)).isEmpty();
    }

    @Test
    void retry_missing_dlq_id_returns_404() {
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                        rest.postForEntity(url("/999999/retry"), null, Void.class),
                HttpStatusCodeException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Long forceOneIntoDlq() {
        AppendEventRequest req = new AppendEventRequest(
                "c1", "alice", 1L, EventType.MESSAGE,
                Map.of("text", "hi"), LocalDateTime.now());
        eventAppendService.append(sessionId, req);

        Mockito.doThrow(new RuntimeException("always-fail")).when(projectionService).apply(Mockito.any());
        outboxPoller.drain();
        Mockito.reset(projectionService);

        List<DeadLetterEvent> rows = dlqRepository.findAllByOrderByMovedAtDesc();
        assertThat(rows).hasSize(1);
        return rows.get(0).getId();
    }

    private String url(String suffix) {
        return "http://localhost:" + port + "/admin/dlq" + suffix;
    }

    // Local DTO mirroring DlqEventResponse for RestTemplate deserialization.
    record DlqEntry(Long id, Long originalEventId, Long sessionId, Long sequence,
                    String eventType, String errorMessage, Integer retryCount,
                    String movedAt) {}
}
