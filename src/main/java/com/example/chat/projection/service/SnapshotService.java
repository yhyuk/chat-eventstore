package com.example.chat.projection.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.config.SnapshotObjectMapperConfig;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.dto.SessionState;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SnapshotService {

    private static final int RETENTION = 3;

    private final EventRepository eventRepository;
    private final SnapshotRepository snapshotRepository;
    private final SessionProjectionRepository projectionRepository;
    private final SessionRepository sessionRepository;
    private final StateEventApplier stateEventApplier;
    private final ObjectMapper objectMapper;
    private final int threshold;

    public SnapshotService(EventRepository eventRepository,
                           SnapshotRepository snapshotRepository,
                           SessionProjectionRepository projectionRepository,
                           SessionRepository sessionRepository,
                           StateEventApplier stateEventApplier,
                           @Qualifier(SnapshotObjectMapperConfig.QUALIFIER) ObjectMapper objectMapper,
                           @Value("${app.snapshot.event-threshold:100}") int threshold) {
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.projectionRepository = projectionRepository;
        this.sessionRepository = sessionRepository;
        this.stateEventApplier = stateEventApplier;
        this.objectMapper = objectMapper;
        this.threshold = threshold;
    }

    // Called by OutboxPoller inside the per-event transaction after projection.apply().
    // Threshold check is cheap; building the snapshot is only triggered every `threshold` events.
    @Transactional
    public void createSnapshotIfNeeded(Event event) {
        Optional<SessionProjection> projection = projectionRepository.findById(event.getSessionId());
        if (projection.isEmpty()) {
            return;
        }
        long messageCount = projection.get().getMessageCount();
        if (messageCount <= 0 || messageCount % threshold != 0) {
            return;
        }
        createSnapshot(event.getSessionId());
    }

    @Transactional
    public void createSnapshot(Long sessionId) {
        Optional<Snapshot> latest = snapshotRepository.findTopBySessionIdOrderByVersionDesc(sessionId);
        long fromSequenceExclusive = latest.map(Snapshot::getLastSequence).orElse(0L);
        // For sessions with 100k+ events, consider paging. D4 task scope: no paging needed.
        List<Event> toApply = eventRepository.findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(
                sessionId, fromSequenceExclusive);
        if (toApply.isEmpty() && latest.isEmpty()) {
            return;
        }

        SessionState state = latest
                .map(s -> deserialize(s.getStateJson()))
                .orElseGet(SessionState::new);

        Event last = null;
        for (Event e : toApply) {
            stateEventApplier.apply(state, e);
            last = e;
        }
        if (last == null) {
            return;
        }

        Integer nextVersion = latest.map(Snapshot::getVersion).orElse(0) + 1;
        Long lastEventId = last.getId();
        Long lastSequence = last.getSequence();

        Snapshot snapshot = Snapshot.builder()
                .sessionId(sessionId)
                .version(nextVersion)
                .lastEventId(lastEventId == null ? 0L : lastEventId)
                .lastSequence(lastSequence)
                .stateJson(serialize(state))
                .build();
        snapshotRepository.save(snapshot);

        // Retain the latest RETENTION snapshots (e.g. 3); older rows are wiped with a Native
        // DELETE because Snapshot is @Immutable and Spring Data derived delete would no-op.
        snapshotRepository.deleteOldSnapshotsNative(sessionId, nextVersion - (RETENTION - 1));
        log.info("Snapshot created: sessionId={}, version={}, lastSequence={}", sessionId, nextVersion, lastSequence);
    }

    // Session end flow -- runs in its own transaction so a snapshot failure does not roll back
    // the session end commit. Caller wraps this in try/catch and logs a warning on failure.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createFinalSnapshot(Long sessionId) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("createFinalSnapshot: session {} not found", sessionId);
            return;
        }
        Optional<Snapshot> latest = snapshotRepository.findTopBySessionIdOrderByVersionDesc(sessionId);
        if (latest.isPresent() && latest.get().getLastSequence() >= session.getLastSequence()) {
            log.info("createFinalSnapshot: skip (already up to date) sessionId={}", sessionId);
            return;
        }
        createSnapshot(sessionId);
    }

    private String serialize(SessionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Snapshot serialize failed", e);
        }
    }

    private SessionState deserialize(String json) {
        try {
            return objectMapper.readValue(json, SessionState.class);
        } catch (JsonProcessingException e) {
            log.warn("Snapshot deserialize failed, starting from empty state: {}", e.getMessage());
            return new SessionState();
        }
    }
}
