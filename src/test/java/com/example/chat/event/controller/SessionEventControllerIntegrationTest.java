package com.example.chat.event.controller;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SessionEventControllerIntegrationTest extends AbstractFullIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final RestTemplate rest = new RestTemplate();

    @BeforeEach
    void cleanDb() {
        eventRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
    }

    @Test
    void append_returns_202_on_new_event() {
        Long sessionId = createSession();
        AppendEventRequest request = request(1L, "c1");

        ResponseEntity<String> response = rest.exchange(
                url(sessionId), HttpMethod.POST, entity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("ACCEPTED");
    }

    @Test
    void append_returns_200_duplicate_on_retry() throws Exception {
        Long sessionId = createSession();
        rest.exchange(url(sessionId), HttpMethod.POST, entity(request(1L, "c1")), String.class);

        ResponseEntity<String> second = rest.exchange(
                url(sessionId), HttpMethod.POST, entity(request(1L, "c1")), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(second.getBody()).get("status").asText())
                .isEqualTo("DUPLICATE_IGNORED");
    }

    @Test
    void append_returns_404_for_missing_session() {
        HttpStatusCodeException ex = catchThrowableOfType(() ->
                rest.exchange(url(999_999L), HttpMethod.POST, entity(request(1L, "c1")), String.class),
                HttpStatusCodeException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getResponseBodyAsString()).contains("SESSION_NOT_FOUND");
    }

    @Test
    void append_returns_409_when_session_ended() {
        Long sessionId = createSession();
        Session session = sessionRepository.findById(sessionId).orElseThrow();
        session.end();
        sessionRepository.saveAndFlush(session);

        HttpStatusCodeException ex = catchThrowableOfType(() ->
                rest.exchange(url(sessionId), HttpMethod.POST, entity(request(1L, "c1")), String.class),
                HttpStatusCodeException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getResponseBodyAsString()).contains("SESSION_ENDED");
    }

    private Long createSession() {
        Session saved = sessionRepository.saveAndFlush(Session.create());
        return saved.getId();
    }

    private String url(Long sessionId) {
        return "http://localhost:" + port + "/sessions/" + sessionId + "/events";
    }

    private HttpEntity<AppendEventRequest> entity(AppendEventRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private AppendEventRequest request(Long sequence, String clientEventId) {
        return new AppendEventRequest(
                clientEventId,
                "alice",
                sequence,
                EventType.MESSAGE,
                Map.of("text", "hi"),
                LocalDateTime.now()
        );
    }
}
