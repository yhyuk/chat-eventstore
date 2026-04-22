package com.example.chat.realtime.pubsub;

import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.registry.SessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessageSubscriber implements MessageListener {

    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            Long sessionId = extractSessionId(channel);
            if (sessionId == null) {
                return;
            }
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            EventBroadcastFrame frame = objectMapper.readValue(body, EventBroadcastFrame.class);
            // Wrap in the same envelope shape ChatWebSocketHandler uses for ACK/ERROR:
            //   { "frameType": "EVENT", "body": { "event": {...} } }
            String outbound = objectMapper.writeValueAsString(new FrameEnvelope(frame.frameType(), frame));
            TextMessage textMessage = new TextMessage(outbound);
            for (WebSocketSession ws : sessionRegistry.getSessions(sessionId)) {
                dispatch(ws, textMessage, sessionId);
            }
        } catch (Exception e) {
            log.warn("Failed to process Redis message: {}", e.getMessage(), e);
        }
    }

    private void dispatch(WebSocketSession ws, TextMessage message, Long sessionId) {
        if (!ws.isOpen()) {
            return;
        }
        try {
            ws.sendMessage(message);
        } catch (IOException e) {
            log.warn("Failed to send broadcast to WS: sessionId={}, wsId={}", sessionId, ws.getId(), e);
        }
    }

    private record FrameEnvelope(String frameType, Object body) {
    }

    private Long extractSessionId(String channel) {
        if (channel == null || !channel.startsWith(RedisMessagePublisher.CHANNEL_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(channel.substring(RedisMessagePublisher.CHANNEL_PREFIX.length()));
        } catch (NumberFormatException e) {
            log.warn("Invalid channel format: {}", channel);
            return null;
        }
    }
}
