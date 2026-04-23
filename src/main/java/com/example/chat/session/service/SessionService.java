package com.example.chat.session.service;

import com.example.chat.common.exception.SessionEndedException;
import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.projection.service.SnapshotService;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final SnapshotService snapshotService;

    @Transactional
    public CreateSessionResponse createSession() {
        Session saved = sessionRepository.saveAndFlush(Session.create());
        log.info("Session created: id={}", saved.getId());
        return CreateSessionResponse.from(saved);
    }

    @Transactional
    public JoinSessionResponse joinSession(Long sessionId, String userId) {
        Session session = sessionRepository.findById(sessionId)
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
        log.info("Session joined: sessionId={}, userId={}", sessionId, userId);
        return JoinSessionResponse.from(saved);
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

        // Final snapshot runs in REQUIRES_NEW so a snapshot failure cannot roll back the
        // authoritative end() commit that was just flushed. Failures are logged and discarded.
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
