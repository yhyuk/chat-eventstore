package com.example.chat.projection.service;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.dto.RebuildResult;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.projection.worker.OutboxPoller;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionRebuildServiceIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired private ProjectionRebuildService rebuildService;
    @Autowired private EventAppendService eventAppendService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SnapshotRepository snapshotRepository;
    @Autowired private SessionProjectionRepository projectionRepository;
    @Autowired private OutboxPoller outboxPoller;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        snapshotRepository.deleteAll();
        projectionRepository.deleteAll();
        eventRepository.deleteAllInBatch();
        participantRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        sessionId = sessionRepository.saveAndFlush(Session.create()).getId();
    }

    @Test
    void rebuild_throws_when_session_missing() {
        assertThatThrownBy(() -> rebuildService.rebuild(9_999_999L))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void rebuild_for_empty_session_clears_read_model_and_returns_zero() {
        RebuildResult r = rebuildService.rebuild(sessionId);

        assertThat(r.replayedEventCount()).isZero();
        assertThat(r.lastSequence()).isZero();
        assertThat(r.snapshotVersion()).isNull();
        assertThat(projectionRepository.findById(sessionId)).isEmpty();
        assertThat(snapshotRepository.findTopBySessionIdOrderByVersionDesc(sessionId)).isEmpty();
    }

    @Test
    void rebuild_restores_projection_matching_event_count() {
        // Stream: 5 MESSAGE + 1 EDIT.
        for (int i = 1; i <= 5; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }
        appendEdit(6L, "c6", "alice", 1L, "edited-msg-1");
        outboxPoller.drain();   // advance the normal pipeline to populate projection

        // Force projection drift: wipe projection row and rebuild.
        projectionRepository.deleteById(sessionId);
        RebuildResult r = rebuildService.rebuild(sessionId);

        assertThat(r.replayedEventCount()).isEqualTo(6);
        assertThat(r.lastSequence()).isEqualTo(6L);
        assertThat(r.snapshotVersion()).isEqualTo(1);

        Optional<SessionProjection> projection = projectionRepository.findById(sessionId);
        assertThat(projection).isPresent();
        // messageCount semantics match ProjectionService.apply(): only MESSAGE events count, EDIT/DELETE do not.
        assertThat(projection.get().getMessageCount()).isEqualTo(5L);
        assertThat(projection.get().getParticipantCount()).isZero();

        Optional<Snapshot> snap = snapshotRepository.findTopBySessionIdOrderByVersionDesc(sessionId);
        assertThat(snap).isPresent();
        assertThat(snap.get().getVersion()).isEqualTo(1);
        assertThat(snap.get().getLastSequence()).isEqualTo(6L);
    }

    @Test
    void rebuild_deletes_old_snapshots_and_creates_fresh_version_one() {
        for (int i = 1; i <= 3; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }
        // Seed a stale snapshot at version 7 to ensure rebuild wipes it.
        snapshotRepository.save(Snapshot.builder()
                .sessionId(sessionId)
                .version(7)
                .lastEventId(999L)
                .lastSequence(999L)
                .stateJson("{}")
                .createdAt(LocalDateTime.now())
                .build());

        rebuildService.rebuild(sessionId);

        Snapshot snap = snapshotRepository.findTopBySessionIdOrderByVersionDesc(sessionId).orElseThrow();
        assertThat(snap.getVersion()).isEqualTo(1);
        assertThat(snap.getLastSequence()).isEqualTo(3L);
        // Only one snapshot survives.
        assertThat(snapshotRepository.countBySessionId(sessionId)).isEqualTo(1);
    }

    private void appendMessage(long sequence, String clientEventId, String user, String text) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, user, sequence, EventType.MESSAGE,
                Map.of("text", text), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }

    private void appendEdit(long sequence, String clientEventId, String user,
                             Long targetSequence, String newText) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, user, sequence, EventType.EDIT,
                Map.of("targetEventId", targetSequence, "newText", newText), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }
}
