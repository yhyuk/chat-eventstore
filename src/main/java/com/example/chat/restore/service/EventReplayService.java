package com.example.chat.restore.service;

import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.config.SnapshotObjectMapperConfig;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.dto.SessionState;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.projection.service.StateEventApplier;
import com.example.chat.restore.dto.TimelineResponse;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// D5 restore API.
// Determinism relies on: (1) StateEventApplier as the single pure-function replay, (2) snapshot
// selected by lastSequence (not createdAt -- avoids async snapshot time skew), (3) events
// filtered by server_received_at <= at with tiebreak on sequence.
@Slf4j
@Service
public class EventReplayService {

    private final SessionRepository sessionRepository;
    private final EventRepository eventRepository;
    private final SnapshotRepository snapshotRepository;
    private final StateEventApplier stateEventApplier;
    private final ObjectMapper objectMapper;

    public EventReplayService(SessionRepository sessionRepository,
                              EventRepository eventRepository,
                              SnapshotRepository snapshotRepository,
                              StateEventApplier stateEventApplier,
                              @Qualifier(SnapshotObjectMapperConfig.QUALIFIER) ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.stateEventApplier = stateEventApplier;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public TimelineResponse restoreAt(Long sessionId, LocalDateTime at) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        LocalDateTime effectiveAt = (at == null) ? LocalDateTime.now() : at;

        Optional<Long> maxSeq = eventRepository
                .findMaxSequenceBySessionIdAndServerReceivedAtLessThanEqual(sessionId, effectiveAt);
        if (maxSeq.isEmpty()) {
            // No events up to `at`: empty state is the correct deterministic answer.
            return TimelineResponse.empty(sessionId, effectiveAt);
        }

        Optional<Snapshot> snapshot = snapshotRepository
                .findTopBySessionIdAndLastSequenceLessThanEqualOrderByVersionDesc(sessionId, maxSeq.get());

        SessionState state = snapshot
                .map(s -> deserialize(s.getStateJson()))
                .orElseGet(SessionState::new);
        long fromSeq = snapshot.map(Snapshot::getLastSequence).orElse(0L);

        List<Event> events = eventRepository
                .findBySessionIdAndSequenceGreaterThanAndServerReceivedAtLessThanEqualOrderBySequenceAsc(
                        sessionId, fromSeq, effectiveAt);
        for (Event e : events) {
            stateEventApplier.apply(state, e);
        }

        return new TimelineResponse(
                sessionId,
                effectiveAt,
                List.copyOf(state.getParticipants().values()),
                List.copyOf(state.getMessages().values()),
                snapshot.isPresent(),
                snapshot.map(Snapshot::getVersion).orElse(null),
                events.size());
    }

    private SessionState deserialize(String json) {
        try {
            return objectMapper.readValue(json, SessionState.class);
        } catch (JsonProcessingException e) {
            // Matches SnapshotService behavior: if a snapshot blob is corrupt, fall back to empty state
            // and rebuild from events. Replay is still deterministic for this call.
            log.warn("Snapshot deserialize failed during restore, falling back to empty state: {}", e.getMessage());
            return new SessionState();
        }
    }
}
