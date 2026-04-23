package com.example.chat.projection.service;

import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.config.SnapshotObjectMapperConfig;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.dto.RebuildResult;
import com.example.chat.projection.dto.SessionState;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// Operational API for rebuilding the read model of a single session from the append-only event log.
//
// Design notes (v3 per ralplan consensus):
// - PESSIMISTIC_WRITE on the sessions row serializes against EventAppendService.updateLastSequence()
//   so concurrent appends wait rather than interleave mid-rebuild.
// - Single in-memory replay pass via StateEventApplier (the shared pure function). We do NOT
//   call ProjectionService.apply() per event -- that would trigger 2N queries for N events.
//   Instead, derive the final counters from the accumulated SessionState and upsert once.
// - Snapshot is written directly (not via SnapshotService.createSnapshot) to avoid re-loading
//   and re-replaying the whole event stream a second time.
// - Events table is READ-ONLY. DLQ rows and projection_status on events are NOT touched here;
//   rebuild restores the read model only, not the pipeline state. (See README.)
@Slf4j
@Service
public class ProjectionRebuildService {

    private final SessionRepository sessionRepository;
    private final EventRepository eventRepository;
    private final SnapshotRepository snapshotRepository;
    private final SessionProjectionRepository projectionRepository;
    private final StateEventApplier stateEventApplier;
    private final ObjectMapper objectMapper;

    public ProjectionRebuildService(SessionRepository sessionRepository,
                                    EventRepository eventRepository,
                                    SnapshotRepository snapshotRepository,
                                    SessionProjectionRepository projectionRepository,
                                    StateEventApplier stateEventApplier,
                                    @Qualifier(SnapshotObjectMapperConfig.QUALIFIER) ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.projectionRepository = projectionRepository;
        this.stateEventApplier = stateEventApplier;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RebuildResult rebuild(Long sessionId) {
        sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        snapshotRepository.deleteAllBySessionIdNative(sessionId);
        projectionRepository.deleteByIdNative(sessionId);

        List<Event> events = eventRepository
                .findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(sessionId, 0L);
        if (events.isEmpty()) {
            log.info("Rebuild: sessionId={} has no events, projection cleared", sessionId);
            return new RebuildResult(sessionId, 0, 0L, null);
        }

        SessionState state = new SessionState();
        LocalDateTime lastMessageAt = null;
        for (Event e : events) {
            stateEventApplier.apply(state, e);
            if (e.getType() == EventType.MESSAGE) {
                lastMessageAt = e.getServerReceivedAt();
            }
        }

        // messageCount semantics must match ProjectionService.apply(): MESSAGE events are +1,
        // EDIT/DELETE do not move the counter. state.messages is keyed by MESSAGE sequence and
        // EDIT/DELETE overwrite the existing entry in place, so size() == total MESSAGE events.
        int participantCount = state.getParticipants().size();
        long messageCount = state.getMessages().size();
        Event last = events.get(events.size() - 1);
        Long lastAppliedEventId = last.getId();

        projectionRepository.upsertProjection(
                sessionId, participantCount, messageCount, lastMessageAt, lastAppliedEventId);

        // Persist a fresh snapshot at version=1 using the already-computed state -- no second replay.
        Snapshot snapshot = Snapshot.builder()
                .sessionId(sessionId)
                .version(1)
                .lastEventId(lastAppliedEventId == null ? 0L : lastAppliedEventId)
                .lastSequence(last.getSequence())
                .stateJson(serialize(state))
                .build();
        snapshotRepository.save(snapshot);

        log.info("Rebuild complete: sessionId={}, events={}, lastSequence={}, snapshotVersion=1",
                sessionId, events.size(), last.getSequence());
        return new RebuildResult(sessionId, events.size(), state.getLastSequence(), 1);
    }

    private String serialize(SessionState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rebuild: snapshot serialize failed", e);
        }
    }
}
