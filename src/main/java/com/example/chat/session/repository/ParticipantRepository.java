package com.example.chat.session.repository;

import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    Optional<Participant> findBySessionAndUserId(Session session, String userId);

    // Lightweight existence check used by HandshakeInterceptor without loading the Session entity.
    boolean existsBySessionIdAndUserId(Long sessionId, String userId);
}
