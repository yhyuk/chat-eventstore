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

// append-only 이벤트 로그로부터 단일 세션의 읽기 모델을 재구성하는 운영 API.
//
// 설계 결정:
// - sessions 행에 PESSIMISTIC_WRITE를 걸어 EventAppendService.updateLastSequence()와
//   직렬화한다 — 재구성 도중 동시 append가 끼어들지 못하게 한다.
// - StateEventApplier(순수 함수)를 통한 단일 인메모리 재생 패스를 사용한다.
//   ProjectionService.apply()를 이벤트별로 호출하지 않는다 — N개 이벤트에 2N 쿼리가 발생한다.
//   대신 누적된 SessionState에서 최종 카운터를 계산하고 한 번만 upsert한다.
// - 이벤트 스트림을 두 번 재생하는 것을 피하기 위해 SnapshotService.createSnapshot() 대신
//   스냅샷을 직접 저장한다.
// - events 테이블은 읽기 전용. DLQ 행과 projection_status는 건드리지 않는다.
//   rebuild는 읽기 모델만 복원하며 파이프라인 상태는 복원하지 않는다 (README 참고).
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
        projectionRepository.deleteBySessionId(sessionId);

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

        // messageCount 계산은 ProjectionService.apply()와 동일해야 한다: MESSAGE 이벤트만 +1,
        // EDIT/DELETE는 카운터를 변경하지 않는다. state.messages는 MESSAGE sequence를 키로 하며
        // EDIT/DELETE는 해당 엔트리를 제자리에서 덮어쓰므로 size() == 총 MESSAGE 이벤트 수다.
        int participantCount = state.getParticipants().size();
        long messageCount = state.getMessages().size();
        Event last = events.get(events.size() - 1);
        Long lastAppliedEventId = last.getId();

        projectionRepository.upsertProjection(
                sessionId, participantCount, messageCount, lastMessageAt, lastAppliedEventId);

        // 이미 계산된 state를 그대로 사용해 version=1 스냅샷을 저장한다 — 두 번째 재생 불필요.
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
