package com.example.chat.restore.controller;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.restore.dto.TimelineResponse;
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

class SessionTimelineControllerIntegrationTest extends AbstractFullIntegrationTest {

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
    void timeline_without_at_returns_current_state() {
        appendMessage(1L, "c1", "alice", "hi");
        appendMessage(2L, "c2", "alice", "there");

        ResponseEntity<TimelineResponse> response = rest.getForEntity(
                url("/sessions/" + sessionId + "/timeline"), TimelineResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().replayedEventCount()).isEqualTo(2);
        assertThat(response.getBody().messages()).hasSize(2);
    }

    @Test
    void timeline_with_iso_at_returns_filtered_state() {
        appendMessage(1L, "c1", "alice", "hi");
        LocalDateTime boundary = eventRepository
                .findBySessionIdAndClientEventId(sessionId, "c1").orElseThrow().getServerReceivedAt();

        ResponseEntity<TimelineResponse> response = rest.getForEntity(
                url("/sessions/" + sessionId + "/timeline?at=" + boundary), TimelineResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().messages()).hasSize(1);
    }

    @Test
    void timeline_returns_404_for_unknown_session() {
        HttpStatusCodeException ex = catchThrowableOfType(
                () -> rest.getForEntity(url("/sessions/9999999/timeline"), Object.class),
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
