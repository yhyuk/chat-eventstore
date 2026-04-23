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

// 결정론적 복원 전략:
// (1) StateEventApplier를 단일 순수 함수 리플레이 경로로 고정,
// (2) 스냅샷은 createdAt 대신 lastSequence 기준 선택 (비동기 스냅샷의 시각 왜곡 방지),
// (3) 이벤트는 server_received_at <= at 필터 후 sequence로 타이브레이크.
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
            // 해당 시각까지 이벤트가 없으면 빈 상태가 올바른 결정론적 결과.
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
            // 스냅샷 블롭이 손상된 경우 빈 상태로 폴백해 이벤트 전체를 재생.
            // SnapshotService와 동일한 정책이며, 이 호출의 결정론은 유지됨.
            log.warn("Snapshot deserialize failed during restore, falling back to empty state: {}", e.getMessage());
            return new SessionState();
        }
    }
}
