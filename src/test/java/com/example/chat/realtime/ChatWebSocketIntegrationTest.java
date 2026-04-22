package com.example.chat.realtime;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ChatWebSocketIntegrationTest extends AbstractFullIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private ObjectMapper objectMapper;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAllInBatch();
        participantRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();

        Session saved = sessionRepository.saveAndFlush(Session.create());
        sessionId = saved.getId();
        participantRepository.saveAndFlush(Participant.builder()
                .session(saved)
                .userId("alice")
                .joinedAt(LocalDateTime.now())
                .build());
    }

    @Test
    void handshake_rejects_non_participant() {
        assertThatThrownBy(() -> connect("/ws/chat?sessionId=" + sessionId + "&userId=carol&lastSequence=0",
                new TestClient()))
                .isInstanceOfAny(ExecutionException.class, RuntimeException.class);
    }

    @Test
    void handshake_rejects_ended_session() {
        Session session = sessionRepository.findById(sessionId).orElseThrow();
        session.end();
        sessionRepository.saveAndFlush(session);

        assertThatThrownBy(() -> connect("/ws/chat?sessionId=" + sessionId + "&userId=alice&lastSequence=0",
                new TestClient()))
                .isInstanceOfAny(ExecutionException.class, RuntimeException.class);
    }

    @Test
    void send_event_receives_ack_and_broadcast() throws Exception {
        TestClient client = new TestClient();
        WebSocketSession ws = connect("/ws/chat?sessionId=" + sessionId + "&userId=alice&lastSequence=0", client);

        String frame = objectMapper.writeValueAsString(Map.of(
                "clientEventId", "c1",
                "sequence", 1,
                "type", EventType.MESSAGE.name(),
                "payload", Map.of("text", "hello"),
                "clientTimestamp", Instant.now().toString(),
                "userId", "alice"));
        ws.sendMessage(new TextMessage(frame));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(client.messages).anyMatch(m -> m.contains("\"ACK\""));
            assertThat(client.messages).anyMatch(m -> m.contains("\"EVENT\""));
        });
        // Event persisted
        assertThat(eventRepository.findBySessionIdAndSequence(sessionId, 1L)).isPresent();

        ws.close();
    }

    @Test
    void duplicate_event_returns_duplicate_ack() throws Exception {
        TestClient client = new TestClient();
        WebSocketSession ws = connect("/ws/chat?sessionId=" + sessionId + "&userId=alice&lastSequence=0", client);

        String frame = objectMapper.writeValueAsString(Map.of(
                "clientEventId", "c1",
                "sequence", 1,
                "type", EventType.MESSAGE.name(),
                "payload", Map.of("text", "hello"),
                "clientTimestamp", Instant.now().toString(),
                "userId", "alice"));
        ws.sendMessage(new TextMessage(frame));
        await().atMost(Duration.ofSeconds(5)).until(() ->
                client.messages.stream().anyMatch(m -> m.contains("\"ACK\"")));

        int beforeSize = client.messages.size();
        ws.sendMessage(new TextMessage(frame));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(client.messages.size()).isGreaterThan(beforeSize);
            String lastAck = client.messages.stream()
                    .filter(m -> m.contains("\"ACK\""))
                    .reduce((a, b) -> b).orElse("");
            assertThat(lastAck).contains("DUPLICATE_IGNORED");
        });
        ws.close();
    }

    private WebSocketSession connect(String path, WebSocketHandler handler) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + path);
        return client.execute(handler, null, uri).get();
    }

    private static class TestClient extends AbstractWebSocketHandler {
        final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            // intentional no-op
        }
    }
}
