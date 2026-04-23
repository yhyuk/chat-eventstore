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
            // Lettuce 커맨드 타임아웃/연결 실패가 여기 해당.
            // 발행 실패 시에도 호출부는 클라이언트에게 ACK를 전송 — broadcast 실패는 graceful degradation으로 처리.
            log.warn("Redis publish failed: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
}
