package com.example.chat.event.repository;

import com.example.chat.common.AbstractIntegrationTest;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventId;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.domain.ProjectionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TestEntityManager em;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Event buildEvent(long sessionId, long sequence, String clientEventId) {
        return Event.builder()
                .sessionId(sessionId)
                .sequence(sequence)
                .clientEventId(clientEventId)
                .userId("user-1")
                .type(EventType.MESSAGE)
                .payload("{\"text\":\"hello\"}")
                .clientTimestamp(LocalDateTime.now())
                .projectionStatus(ProjectionStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(LocalDateTime.now())
                .build();
    }

    @Test
    void composite_pk_save_and_find() {
        Event event = buildEvent(100L, 1L, "client-uuid-1");

        eventRepository.save(event);
        em.flush();
        em.clear();

        Event found = eventRepository.findById(new EventId(100L, 1L)).orElseThrow();
        assertThat(found.getSessionId()).isEqualTo(100L);
        assertThat(found.getSequence()).isEqualTo(1L);
        assertThat(found.getClientEventId()).isEqualTo("client-uuid-1");
    }

    @Test
    void id_auto_generated_after_flush() {
        // Non-PK surrogate id is AUTO_INCREMENT -- @Generated(INSERT) causes Hibernate to SELECT after INSERT
        Event event = buildEvent(101L, 1L, "client-uuid-auto");

        eventRepository.save(event);
        em.flush();
        em.clear();

        Event found = eventRepository.findById(new EventId(101L, 1L)).orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getId()).isGreaterThan(0L);
    }

    @Test
    void unique_client_event_id_violation_throws() {
        Event first = buildEvent(102L, 1L, "duplicate-uuid");
        Event second = buildEvent(102L, 2L, "duplicate-uuid");

        eventRepository.save(first);
        em.flush();

        assertThatThrownBy(() -> {
            eventRepository.save(second);
            em.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class,
                jakarta.persistence.PersistenceException.class);
    }

    @Test
    void json_payload_roundtrip() throws Exception {
        String payload = "{\"text\":\"hello\",\"count\":42}";
        Event event = Event.builder()
                .sessionId(103L)
                .sequence(1L)
                .clientEventId("client-uuid-json")
                .userId("user-1")
                .type(EventType.MESSAGE)
                .payload(payload)
                .clientTimestamp(LocalDateTime.now())
                .projectionStatus(ProjectionStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(LocalDateTime.now())
                .build();

        eventRepository.save(event);
        em.flush();
        em.clear();

        Event found = eventRepository.findById(new EventId(103L, 1L)).orElseThrow();

        // MySQL normalises JSON column whitespace -- compare field values, not raw string
        JsonNode node = MAPPER.readTree(found.getPayload());
        assertThat(node.get("text").asText()).isEqualTo("hello");
        assertThat(node.get("count").asInt()).isEqualTo(42);
    }
}
