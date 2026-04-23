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

    /**
     * OutboxPoller가 projection.apply() 직후 이벤트별 트랜잭션 안에서 호출.
     * 임계치 체크는 저렴하므로 매번 수행하되, 실제 스냅샷 생성은 {@code threshold} 이벤트마다 한 번씩만 트리거된다.
     */
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
        // 세션당 10만 건 이상 이벤트가 쌓이면 페이징 도입 검토 필요. D4 범위에서는 페이징 없이 전량 로드.
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

        // 최신 RETENTION개(예: 3)만 보존. Snapshot 엔티티가 @Immutable이라 Spring Data 파생 delete가
        // 무시되므로 Native DELETE로 직접 제거해야 함.
        snapshotRepository.deleteOldSnapshotsNative(sessionId, nextVersion - (RETENTION - 1));
        log.info("Snapshot created: sessionId={}, version={}, lastSequence={}", sessionId, nextVersion, lastSequence);
    }

    /**
     * 세션 종료 시 호출되는 최종 스냅샷.
     *
     * <p>스냅샷 생성 실패가 세션 종료 커밋을 롤백시키지 않도록 별도 트랜잭션(REQUIRES_NEW)으로 분리.
     * 호출 측에서 try/catch로 감싸 실패 시 경고 로그만 남기도록 약속한다.
     */
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
