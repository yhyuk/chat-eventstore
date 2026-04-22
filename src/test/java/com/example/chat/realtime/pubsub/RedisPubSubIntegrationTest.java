package com.example.chat.realtime.pubsub;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.registry.SessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisPubSubIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired
    private RedisMessagePublisher publisher;

    @MockBean
    private SessionRegistry sessionRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publish_reaches_single_instance_subscriber() throws Exception {
        Long sessionId = 777L;
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        when(sessionRegistry.getSessions(sessionId)).thenReturn(Set.of(ws));
        // Other sessionIds must not receive this message.
        when(sessionRegistry.getSessions(777L + 1)).thenReturn(Collections.emptySet());

        EventBroadcastFrame.EventPayload payload = new EventBroadcastFrame.EventPayload(
                1L, sessionId, 1L, "c1", "alice", "MESSAGE",
                Map.of("text", "hi"),
                Instant.now(),
                Instant.now());
        EventBroadcastFrame frame = new EventBroadcastFrame(payload);

        publisher.publish(sessionId, frame);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(ws, atLeastOnce()).sendMessage(captor.capture())
        );
        String delivered = captor.getValue().getPayload();
        assertThat(delivered).contains("MESSAGE");
        assertThat(delivered).contains("alice");
    }
}
