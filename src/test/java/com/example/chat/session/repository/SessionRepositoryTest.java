package com.example.chat.session.repository;

import com.example.chat.common.AbstractIntegrationTest;
import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void save_and_find_by_id() {
        Session session = Session.builder()
                .status(SessionStatus.ACTIVE)
                .lastSequence(0L)
                .build();

        Session saved = sessionRepository.save(session);
        em.flush();
        em.clear();

        Session found = sessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(found.getLastSequence()).isEqualTo(0L);
        // @Generated(INSERT) -- DB populates createdAt via DEFAULT NOW(3)
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void version_initialized_on_persist() {
        Session session = Session.builder()
                .status(SessionStatus.ACTIVE)
                .lastSequence(0L)
                .build();

        sessionRepository.save(session);
        em.flush();
        em.clear();

        Session found = sessionRepository.findById(session.getId()).orElseThrow();

        // @Version field is initialized to 0 by DB DEFAULT after first INSERT
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getVersion()).isGreaterThanOrEqualTo(0L);
    }
}
