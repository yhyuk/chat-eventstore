package com.example.chat.session.service;

import com.example.chat.common.exception.SessionEndedException;
import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.service.SnapshotService;
import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.pubsub.RedisMessagePublisher;
import com.example.chat.realtime.service.RecentCacheService;
import com.example.chat.realtime.service.ResumeService;
import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.dto.CreateSessionResponse;
import com.example.chat.session.dto.EndSessionResponse;
import com.example.chat.session.dto.JoinSessionResponse;
import com.example.chat.session.dto.SessionListResponse;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final SnapshotService snapshotService;
    private final EventAppendService eventAppendService;
    private final RedisMessagePublisher publisher;
    private final RecentCacheService recentCacheService;
    private final ResumeService resumeService;

    @Transactional
    public CreateSessionResponse createSession() {
        Session saved = sessionRepository.saveAndFlush(Session.create());
        log.info("Session created: id={}", saved.getId());
        return CreateSessionResponse.from(saved);
    }

    // 세션 행에 PESSIMISTIC_WRITE 락을 걸어 JOIN 이벤트의 sequence 채번(last_sequence+1)과
    // WebSocket append 경로의 updateLastSequence()가 동시 실행돼도 PK 충돌이 나지 않게 한다.
    // 기존 participant가 있는 경우(재참여)에는 이벤트를 새로 쓰지 않아 멱등성을 유지한다.
    @Transactional
    public JoinSessionResponse joinSession(Long sessionId, String userId) {
        Session session = sessionRepository.findWithLockById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        if (session.isEnded()) {
            throw new SessionEndedException(sessionId);
        }

        Participant existing = participantRepository.findBySessionAndUserId(session, userId).orElse(null);
        if (existing != null) {
            log.info("Session join idempotent: sessionId={}, userId={}", sessionId, userId);
            return JoinSessionResponse.from(existing);
        }

        Participant participant = Participant.builder()
                .session(session)
                .userId(userId)
                .joinedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS))
                .build();
        Participant saved = participantRepository.save(participant);

        Long joinSequence = appendJoinEvent(session, userId);

        log.info("Session joined: sessionId={}, userId={}, lastSequence={}", sessionId, userId, joinSequence);
        // updateLastSequence(JPQL)가 clearAutomatically=true로 영속성 컨텍스트를 비우므로,
        // 엔티티의 lastSequence는 stale일 수 있다. JOIN append가 실제로 점유한 sequence를 직접 응답에 실어준다.
        return JoinSessionResponse.of(saved, joinSequence);
    }

    // HTTP /join 경로에서 JOIN 이벤트를 events 테이블에 append하고 Redis로 브로드캐스트한다.
    // sequence는 세션 락 하에 last_sequence+1로 채번 — 동일 트랜잭션의 SELECT ... FOR UPDATE가 선행되므로 경합 없음.
    // 호출자가 응답 본문에 lastSequence를 실을 수 있도록 실제 점유한 sequence를 반환한다.
    private Long appendJoinEvent(Session session, String userId) {
        Long sessionId = session.getId();
        Long sequence = session.getLastSequence() + 1L;
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        AppendEventRequest request = new AppendEventRequest(
                UUID.randomUUID().toString(),
                userId,
                sequence,
                EventType.JOIN,
                Map.of(),
                now
        );

        AppendResult result = eventAppendService.append(sessionId, request);
        Event saved = result.savedEvent();
        EventBroadcastFrame.EventPayload payload = resumeService.toPayload(saved);
        publisher.publish(sessionId, new EventBroadcastFrame(payload));
        recentCacheService.append(sessionId, saved.getSequence(), payload);
        return saved.getSequence();
    }

    @Transactional
    public EndSessionResponse endSession(Long sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        if (session.isEnded()) {
            log.info("Session end idempotent: id={}", sessionId);
            return EndSessionResponse.from(session);
        }

        session.end();
        Session saved = sessionRepository.saveAndFlush(session);
        log.info("Session ended: id={}", sessionId);

        // 최종 스냅샷은 REQUIRES_NEW 트랜잭션으로 실행해 스냅샷 실패가 이미 커밋된 end()를 롤백하지 않도록 한다.
        try {
            snapshotService.createFinalSnapshot(sessionId);
        } catch (Exception ex) {
            log.warn("Final snapshot creation failed for session {}: {}", sessionId, ex.getMessage());
        }
        return EndSessionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public SessionListResponse listSessions(SessionStatus status,
                                             LocalDateTime from,
                                             LocalDateTime to,
                                             String participant,
                                             int page,
                                             int size) {
        return sessionRepository.search(status, from, to, participant, page, size);
    }
}
