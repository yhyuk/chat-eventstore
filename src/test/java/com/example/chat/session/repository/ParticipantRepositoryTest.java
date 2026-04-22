package com.example.chat.session.repository;

import com.example.chat.common.AbstractIntegrationTest;
import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParticipantRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void save_and_find_by_id() {
        Session session = sessionRepository.save(Session.builder()
                .status(SessionStatus.ACTIVE)
                .lastSequence(0L)
                .build());
        em.flush();

        Participant participant = Participant.builder()
                .session(session)
                .userId("user-1")
                .joinedAt(LocalDateTime.now())
                .build();

        Participant saved = participantRepository.save(participant);
        em.flush();
        em.clear();

        Participant found = participantRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getUserId()).isEqualTo("user-1");
        assertThat(found.getSession().getId()).isEqualTo(session.getId());
    }

    @Test
    void unique_session_user_violation_throws() {
        Session session = sessionRepository.save(Session.builder()
                .status(SessionStatus.ACTIVE)
                .lastSequence(0L)
                .build());
        em.flush();

        Participant first = Participant.builder()
                .session(session)
                .userId("dup-user")
                .joinedAt(LocalDateTime.now())
                .build();
        participantRepository.save(first);
        em.flush();

        Participant duplicate = Participant.builder()
                .session(session)
                .userId("dup-user")
                .joinedAt(LocalDateTime.now())
                .build();

        // UK uk_session_user = (session_id, user_id) -- last line of defense against double-join
        assertThatThrownBy(() -> {
            participantRepository.save(duplicate);
            em.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class,
                jakarta.persistence.PersistenceException.class);
    }
}
