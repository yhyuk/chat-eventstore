package com.example.chat.projection;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.repository.DeadLetterEventRepository;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.projection.worker.OutboxPoller;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import com.example.chat.session.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineEndToEndIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired private EventAppendService eventAppendService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionService sessionService;
    @Autowired private SessionProjectionRepository projectionRepository;
    @Autowired private SnapshotRepository snapshotRepository;
    @Autowired private DeadLetterEventRepository dlqRepository;
    @Autowired private OutboxPoller outboxPoller;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        snapshotRepository.deleteAll();
        dlqRepository.deleteAll();
        projectionRepository.deleteAll();
        eventRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        sessionId = sessionRepository.saveAndFlush(Session.create()).getId();
    }

    @Test
    void tombstone_edit_does_not_increment_message_count() {
        append(1L, "c1", EventType.MESSAGE, Map.of("text", "hello"));
        append(2L, "c2", EventType.MESSAGE, Map.of("text", "world"));
        append(3L, "c3", EventType.EDIT, Map.of("targetEventId", 1L, "newText", "edited"));

        outboxPoller.drain();

        Optional<SessionProjection> projection = projectionRepository.findById(sessionId);
        assertThat(projection).isPresent();
        assertThat(projection.get().getMessageCount()).isEqualTo(2L);
    }

    @Test
    void end_session_triggers_final_snapshot_in_new_transaction() {
        append(1L, "c1", EventType.MESSAGE, Map.of("text", "hi"));
        outboxPoller.drain();

        sessionService.endSession(sessionId);

        Optional<Snapshot> snap = snapshotRepository.findTopBySessionIdOrderByVersionDesc(sessionId);
        assertThat(snap).isPresent();
        // session end must have committed independently of the snapshot transaction outcome.
        Session finished = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(finished.isEnded()).isTrue();
    }

    private void append(long sequence, String clientEventId, EventType type, Map<String, Object> payload) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, "alice", sequence, type, payload, LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }
}
