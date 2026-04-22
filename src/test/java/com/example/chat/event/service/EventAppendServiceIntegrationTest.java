package com.example.chat.event.service;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.common.exception.DuplicateEventException;
import com.example.chat.common.exception.InvalidSequenceException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventAppendServiceIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired
    private EventAppendService service;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SessionRepository sessionRepository;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        Session session = Session.create();
        Session saved = sessionRepository.saveAndFlush(session);
        this.sessionId = saved.getId();
    }

    @Test
    void append_accepted_updates_last_sequence() {
        AppendResult result = service.append(sessionId, request(1L, "c1"));

        assertThat(result.status()).isEqualTo(AppendResult.Status.ACCEPTED);
        assertThat(result.savedEvent().getId()).isNotNull();
        Session refreshed = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(refreshed.getLastSequence()).isEqualTo(1L);
    }

    @Test
    void append_greatest_preserves_max_even_on_backfill() {
        service.append(sessionId, request(5L, "c1"));
        service.append(sessionId, request(3L, "c2"));

        Session refreshed = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(refreshed.getLastSequence()).isEqualTo(5L);
    }

    @Test
    void append_duplicate_same_clientId_same_sequence_throws_duplicate() {
        service.append(sessionId, request(1L, "c1"));

        assertThatThrownBy(() -> service.append(sessionId, request(1L, "c1")))
                .isInstanceOf(DuplicateEventException.class);
    }

    @Test
    void append_same_sequence_different_client_id_throws_invalid_sequence() {
        service.append(sessionId, request(1L, "c1"));

        assertThatThrownBy(() -> service.append(sessionId, request(1L, "c2")))
                .isInstanceOf(InvalidSequenceException.class);
    }

    @Test
    void append_same_client_id_different_sequence_throws_duplicate() {
        service.append(sessionId, request(1L, "c1"));

        assertThatThrownBy(() -> service.append(sessionId, request(2L, "c1")))
                .isInstanceOf(DuplicateEventException.class);
    }

    @Test
    void append_rejects_zero_sequence() {
        assertThatThrownBy(() -> service.append(sessionId, request(0L, "c-zero")))
                .isInstanceOf(InvalidSequenceException.class);
    }

    @Test
    void append_persists_row_visible_via_derived_queries() {
        AppendResult result = service.append(sessionId, request(7L, "c7"));

        Optional<Event> bySeq = eventRepository.findBySessionIdAndSequence(sessionId, 7L);
        Optional<Event> byClient = eventRepository.findBySessionIdAndClientEventId(sessionId, "c7");
        assertThat(bySeq).isPresent();
        assertThat(byClient).isPresent();
        assertThat(bySeq.get().getId()).isEqualTo(result.savedEvent().getId());
    }

    private AppendEventRequest request(Long sequence, String clientEventId) {
        return new AppendEventRequest(
                clientEventId,
                "alice",
                sequence,
                EventType.MESSAGE,
                Map.of("text", "hello-" + sequence),
                LocalDateTime.now()
        );
    }
}
