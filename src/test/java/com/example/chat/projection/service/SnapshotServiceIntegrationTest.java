package com.example.chat.projection.service;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.config.SnapshotObjectMapperConfig;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.dto.SessionState;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.projection.worker.OutboxPoller;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotServiceIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired private SnapshotService snapshotService;
    @Autowired private SnapshotRepository snapshotRepository;
    @Autowired private SessionProjectionRepository projectionRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventAppendService eventAppendService;
    @Autowired private OutboxPoller outboxPoller;

    @Autowired
    @Qualifier(SnapshotObjectMapperConfig.QUALIFIER)
    private ObjectMapper snapshotMapper;

    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        snapshotRepository.deleteAll();
        projectionRepository.deleteAll();
        eventRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        sessionId = sessionRepository.saveAndFlush(Session.create()).getId();
    }

    @Test
    void explicit_create_snapshot_builds_state_json_from_events() throws Exception {
        appendMessage(1L, "c1", "hello");
        appendMessage(2L, "c2", "world");
        outboxPoller.drain();

        snapshotService.createSnapshot(sessionId);

        Snapshot snap = snapshotRepository.findTopBySessionIdOrderByVersionDesc(sessionId).orElseThrow();
        assertThat(snap.getVersion()).isEqualTo(1);
        assertThat(snap.getLastSequence()).isEqualTo(2L);

        // Deserialize and verify TreeMap / LinkedHashMap types survive the round trip.
        SessionState restored = snapshotMapper.readValue(snap.getStateJson(), SessionState.class);
        assertThat(restored.getParticipants()).isInstanceOf(TreeMap.class);
        assertThat(restored.getMessages()).isInstanceOf(LinkedHashMap.class);
        assertThat(restored.getMessages()).hasSize(2);
    }

    @Test
    void native_delete_prunes_old_snapshots_past_retention() {
        // Seed snapshots manually; real replay not needed here.
        for (int v = 1; v <= 5; v++) {
            snapshotRepository.save(Snapshot.builder()
                    .sessionId(sessionId)
                    .version(v)
                    .lastEventId((long) v)
                    .lastSequence((long) v)
                    .stateJson("{}")
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        // Retention 3: delete versions < 3 when latest is 5. Wrap in explicit tx so the
        // @Modifying query has an active transaction (same as production SnapshotService uses).
        Integer deleted = tx.execute(status -> snapshotRepository.deleteOldSnapshotsNative(sessionId, 3));
        assertThat(deleted).isEqualTo(2);

        List<Snapshot> remaining = snapshotRepository.findAll().stream()
                .filter(s -> s.getSessionId().equals(sessionId)).toList();
        assertThat(remaining).extracting(Snapshot::getVersion).containsExactlyInAnyOrder(3, 4, 5);
    }

    private void appendMessage(long sequence, String clientEventId, String text) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, "alice", sequence, EventType.MESSAGE,
                Map.of("text", text), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }
}
