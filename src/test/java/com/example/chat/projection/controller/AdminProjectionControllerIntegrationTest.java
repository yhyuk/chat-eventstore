package com.example.chat.projection.controller;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.dto.RebuildResult;
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

class AdminProjectionControllerIntegrationTest extends AbstractFullIntegrationTest {

    @LocalServerPort private int port;

    @Autowired private EventAppendService eventAppendService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private SessionProjectionRepository projectionRepository;
    @Autowired private SnapshotRepository snapshotRepository;

    private final RestTemplate rest = new RestTemplate();

    @BeforeEach
    void setUp() {
        snapshotRepository.deleteAll();
        projectionRepository.deleteAll();
        eventRepository.deleteAllInBatch();
        participantRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
    }

    @Test
    void rebuild_returns_200_with_counts_for_existing_session() {
        Long sessionId = sessionRepository.saveAndFlush(Session.create()).getId();
        appendMessage(sessionId, 1L, "c1", "alice", "hi");
        appendMessage(sessionId, 2L, "c2", "alice", "there");

        ResponseEntity<RebuildResult> response = rest.postForEntity(
                url("/admin/projections/rebuild?sessionId=" + sessionId),
                null, RebuildResult.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().replayedEventCount()).isEqualTo(2);
        assertThat(response.getBody().snapshotVersion()).isEqualTo(1);
    }

    @Test
    void rebuild_returns_404_for_unknown_session() {
        HttpStatusCodeException ex = catchThrowableOfType(
                () -> rest.postForEntity(url("/admin/projections/rebuild?sessionId=9999999"),
                        null, Object.class),
                HttpStatusCodeException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void appendMessage(Long sessionId, long sequence, String clientEventId, String user, String text) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, user, sequence, EventType.MESSAGE,
                Map.of("text", text), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }
}
