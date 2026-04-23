package com.example.chat.session.repository;

import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    Optional<Participant> findBySessionAndUserId(Session session, String userId);

    // HandshakeInterceptor에서 Session 엔티티를 로드하지 않고 참여 여부만 확인하는 경량 조회.
    boolean existsBySessionIdAndUserId(Long sessionId, String userId);
}
