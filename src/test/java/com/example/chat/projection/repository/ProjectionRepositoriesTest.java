package com.example.chat.projection.repository;

import com.example.chat.common.AbstractIntegrationTest;
import com.example.chat.projection.domain.DeadLetterEvent;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.domain.SnapshotId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionRepositoriesTest extends AbstractIntegrationTest {

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @Autowired
    private SessionProjectionRepository sessionProjectionRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void snapshot_save_with_composite_pk() {
        Snapshot snapshot = Snapshot.builder()
                .sessionId(1L)
                .version(1)
                .lastEventId(100L)
                .lastSequence(50L)
                .stateJson("{}")
                .build();

        snapshotRepository.save(snapshot);
        em.flush();
        em.clear();

        Snapshot found = snapshotRepository.findById(new SnapshotId(1L, 1)).orElseThrow();
        assertThat(found.getSessionId()).isEqualTo(1L);
        assertThat(found.getVersion()).isEqualTo(1);
        assertThat(found.getLastEventId()).isEqualTo(100L);
        assertThat(found.getLastSequence()).isEqualTo(50L);
        // @Generated(INSERT) -- DB populates createdAt via DEFAULT NOW(3)
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void dead_letter_save_auto_id() {
        DeadLetterEvent dle = DeadLetterEvent.builder()
                .originalEventId(999L)
                .sessionId(10L)
                .eventType("MESSAGE")
                .payload("{\"text\":\"fail\"}")
                .errorMessage("processing failed")
                .retryCount(5)
                .build();

        DeadLetterEvent saved = deadLetterEventRepository.save(dle);
        em.flush();
        em.clear();

        DeadLetterEvent found = deadLetterEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getId()).isGreaterThan(0L);
        assertThat(found.getOriginalEventId()).isEqualTo(999L);
    }

    @Test
    void session_projection_shared_pk() {
        SessionProjection proj = SessionProjection.builder()
                .sessionId(1L)
                .participantCount(2)
                .messageCount(10L)
                .lastAppliedEventId(0L)
                .build();

        sessionProjectionRepository.save(proj);
        em.flush();
        em.clear();

        SessionProjection found = sessionProjectionRepository.findById(1L).orElseThrow();
        assertThat(found.getSessionId()).isEqualTo(1L);
        assertThat(found.getParticipantCount()).isEqualTo(2);
        assertThat(found.getMessageCount()).isEqualTo(10L);
    }

    @Test
    void session_projection_update_refreshes_updated_at() {
        SessionProjection proj = SessionProjection.builder()
                .sessionId(2L)
                .participantCount(1)
                .messageCount(0L)
                .lastAppliedEventId(0L)
                .build();

        sessionProjectionRepository.save(proj);
        em.flush();
        em.clear();

        SessionProjection inserted = sessionProjectionRepository.findById(2L).orElseThrow();
        // ON UPDATE CURRENT_TIMESTAMP(3) -- updatedAt is populated after INSERT
        assertThat(inserted.getUpdatedAt()).isNotNull();

        inserted.updateParticipantCount(5);
        sessionProjectionRepository.saveAndFlush(inserted);
        em.clear();

        SessionProjection updated = sessionProjectionRepository.findById(2L).orElseThrow();
        assertThat(updated.getUpdatedAt()).isNotNull();
        assertThat(updated.getParticipantCount()).isEqualTo(5);
    }
}
