package com.example.chat.event.controller;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.MessageHistoryResponse;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class MessageHistoryControllerIntegrationTest extends AbstractFullIntegrationTest {

    @LocalServerPort private int port;

    @Autowired private EventAppendService eventAppendService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private SnapshotRepository snapshotRepository;
    @Autowired private SessionProjectionRepository projectionRepository;

    private final RestTemplate rest = new RestTemplate();
    private Long sessionId;

    @BeforeEach
    void setUp() {
        snapshotRepository.deleteAll();
        projectionRepository.deleteAll();
        eventRepository.deleteAllInBatch();
        participantRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        sessionId = sessionRepository.saveAndFlush(Session.create()).getId();
    }

    @Test
    void empty_session_returns_empty_response() {
        ResponseEntity<MessageHistoryResponse> response = rest.getForEntity(
                url("/sessions/" + sessionId + "/messages"), MessageHistoryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().messages()).isEmpty();
        assertThat(response.getBody().hasMore()).isFalse();
        assertThat(response.getBody().nextBefore()).isNull();
    }

    @Test
    void first_page_returns_latest_messages_in_desc_order() {
        for (long i = 1; i <= 30; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }

        ResponseEntity<MessageHistoryResponse> response = rest.getForEntity(
                url("/sessions/" + sessionId + "/messages?limit=10"), MessageHistoryResponse.class);

        MessageHistoryResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.messages()).hasSize(10);
        // 최신부터 sequence 30, 29, ..., 21 순서
        assertThat(body.messages().get(0).sequence()).isEqualTo(30L);
        assertThat(body.messages().get(9).sequence()).isEqualTo(21L);
        assertThat(body.hasMore()).isTrue();
        assertThat(body.nextBefore()).isEqualTo(21L);
    }

    @Test
    void cursor_pagination_walks_backwards_through_history() {
        for (long i = 1; i <= 25; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }

        // Page 1: 최신 10개 (sequence 25..16)
        MessageHistoryResponse p1 = rest.getForEntity(
                url("/sessions/" + sessionId + "/messages?limit=10"),
                MessageHistoryResponse.class).getBody();
        assertThat(p1).isNotNull();
        assertThat(p1.messages().get(0).sequence()).isEqualTo(25L);
        assertThat(p1.nextBefore()).isEqualTo(16L);
        assertThat(p1.hasMore()).isTrue();

        // Page 2: before=16 → sequence 15..6
        MessageHistoryResponse p2 = rest.getForEntity(
                url("/sessions/" + sessionId + "/messages?before=" + p1.nextBefore() + "&limit=10"),
                MessageHistoryResponse.class).getBody();
        assertThat(p2).isNotNull();
        assertThat(p2.messages().get(0).sequence()).isEqualTo(15L);
        assertThat(p2.messages().get(9).sequence()).isEqualTo(6L);
        assertThat(p2.nextBefore()).isEqualTo(6L);
        assertThat(p2.hasMore()).isTrue();

        // Page 3: before=6 → sequence 5..1, hasMore=false
        MessageHistoryResponse p3 = rest.getForEntity(
                url("/sessions/" + sessionId + "/messages?before=" + p2.nextBefore() + "&limit=10"),
                MessageHistoryResponse.class).getBody();
        assertThat(p3).isNotNull();
        assertThat(p3.messages()).hasSize(5);
        assertThat(p3.messages().get(0).sequence()).isEqualTo(5L);
        assertThat(p3.messages().get(4).sequence()).isEqualTo(1L);
        assertThat(p3.hasMore()).isFalse();
        assertThat(p3.nextBefore()).isNull();
    }

    @Test
    void limit_is_capped_at_max() {
        for (long i = 1; i <= 150; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }

        // limit=500을 요청해도 최대 100개로 제한된다.
        MessageHistoryResponse body = rest.getForEntity(
                url("/sessions/" + sessionId + "/messages?limit=500"),
                MessageHistoryResponse.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body.messages()).hasSize(100);
        assertThat(body.hasMore()).isTrue();
    }

    @Test
    void unknown_session_returns_404() {
        HttpStatusCodeException ex = catchThrowableOfType(
                () -> rest.getForEntity(url("/sessions/9999999/messages"), Object.class),
                HttpStatusCodeException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void appendMessage(long sequence, String clientEventId, String user, String text) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, user, sequence, EventType.MESSAGE,
                Map.of("text", text), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }
}
