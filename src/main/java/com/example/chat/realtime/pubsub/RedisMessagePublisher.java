package com.example.chat.realtime.pubsub;

import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessagePublisher {

    public static final String CHANNEL_PREFIX = "session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(Long sessionId, EventBroadcastFrame frame) {
        try {
            String json = objectMapper.writeValueAsString(frame);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + sessionId, json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize broadcast frame: sessionId={}", sessionId, e);
        } catch (Exception e) {
            // Lettuce command timeout / connection failure falls here.
            // Downstream callers still ACK the client — broadcast failure is graceful degradation.
            log.warn("Redis publish failed: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
}
