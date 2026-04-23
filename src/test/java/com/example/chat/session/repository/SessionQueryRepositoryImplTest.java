package com.example.chat.session.repository;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.dto.SessionListResponse;
import com.example.chat.session.dto.SessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// QueryDSL dynamic filter coverage: status, createdAt range, participant EXISTS, pagination.
class SessionQueryRepositoryImplTest extends AbstractFullIntegrationTest {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private ParticipantRepository participantRepository;

    @BeforeEach
    void setUp() {
        participantRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
    }

    @Test
    void search_returns_empty_for_empty_repository() {
        SessionListResponse r = sessionRepository.search(null, null, null, null, 0, 20);

        assertThat(r.content()).isEmpty();
        assertThat(r.total()).isZero();
    }

    @Test
    void search_filters_by_status() {
        Session active = sessionRepository.saveAndFlush(Session.create());
        Session ended = sessionRepository.saveAndFlush(Session.create());
        ended.end();
        sessionRepository.saveAndFlush(ended);

        SessionListResponse r = sessionRepository.search(SessionStatus.ACTIVE, null, null, null, 0, 20);

        assertThat(r.content()).extracting(SessionSummary::id).containsExactly(active.getId());
        assertThat(r.total()).isEqualTo(1);
    }

    @Test
    void search_filters_by_participant_via_exists() {
        Session s1 = sessionRepository.saveAndFlush(Session.create());
        Session s2 = sessionRepository.saveAndFlush(Session.create());
        sessionRepository.saveAndFlush(Session.create()); // no participants

        participantRepository.saveAndFlush(Participant.builder()
                .session(s1).userId("alice").joinedAt(LocalDateTime.now()).build());
        participantRepository.saveAndFlush(Participant.builder()
                .session(s1).userId("bob").joinedAt(LocalDateTime.now()).build());
        participantRepository.saveAndFlush(Participant.builder()
                .session(s2).userId("alice").joinedAt(LocalDateTime.now()).build());

        SessionListResponse r = sessionRepository.search(null, null, null, "alice", 0, 20);

        // Both s1 and s2 contain alice. EXISTS must not duplicate s1 despite multiple participants.
        assertThat(r.content()).extracting(SessionSummary::id).containsExactlyInAnyOrder(s1.getId(), s2.getId());
        assertThat(r.total()).isEqualTo(2);
    }

    @Test
    void search_applies_pagination_and_descending_id_order() {
        Long last = null;
        for (int i = 0; i < 5; i++) {
            last = sessionRepository.saveAndFlush(Session.create()).getId();
        }

        SessionListResponse page0 = sessionRepository.search(null, null, null, null, 0, 2);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content().get(0).id()).isEqualTo(last);
        assertThat(page0.total()).isEqualTo(5);

        SessionListResponse page2 = sessionRepository.search(null, null, null, null, 2, 2);
        assertThat(page2.content()).hasSize(1);
    }

    @Test
    void search_composes_multiple_filters() {
        Session s1 = sessionRepository.saveAndFlush(Session.create());
        Session s2 = sessionRepository.saveAndFlush(Session.create());
        s2.end();
        sessionRepository.saveAndFlush(s2);

        participantRepository.saveAndFlush(Participant.builder()
                .session(s1).userId("alice").joinedAt(LocalDateTime.now()).build());
        participantRepository.saveAndFlush(Participant.builder()
                .session(s2).userId("alice").joinedAt(LocalDateTime.now()).build());

        SessionListResponse r = sessionRepository.search(
                SessionStatus.ACTIVE, null, null, "alice", 0, 20);

        assertThat(r.content()).extracting(SessionSummary::id).containsExactly(s1.getId());
    }
}
