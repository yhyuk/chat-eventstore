package com.example.chat.realtime.service;

import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class RecentCacheService {

    private static final String KEY_PREFIX = "session:";
    private static final String KEY_SUFFIX = ":recent";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int cacheSize;

    public RecentCacheService(StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              @Value("${app.recent-cache.size:50}") int cacheSize) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheSize = cacheSize;
    }

    public void append(Long sessionId, Long sequence, EventBroadcastFrame.EventPayload payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            String key = keyOf(sessionId);
            ZSetOperations<String, String> ops = redisTemplate.opsForZSet();
            ops.add(key, json, sequence.doubleValue());
            // Keep only the newest N entries — trim removes oldest by rank (0 = oldest, -1 = newest).
            ops.removeRange(key, 0, -(cacheSize + 1L));
        } catch (JsonProcessingException e) {
            log.warn("Recent cache serialize failed: sessionId={}", sessionId, e);
        } catch (Exception e) {
            log.warn("Recent cache append failed: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    public List<EventBroadcastFrame.EventPayload> getEventsSince(Long sessionId, Long lastSequence) {
        try {
            double min = lastSequence.doubleValue() + 1d;
            Set<String> jsons = redisTemplate.opsForZSet()
                    .rangeByScore(keyOf(sessionId), min, Double.POSITIVE_INFINITY);
            if (jsons == null || jsons.isEmpty()) {
                return Collections.emptyList();
            }
            List<EventBroadcastFrame.EventPayload> out = new ArrayList<>(jsons.size());
            for (String json : jsons) {
                out.add(objectMapper.readValue(json, EventBroadcastFrame.EventPayload.class));
            }
            return out;
        } catch (Exception e) {
            log.warn("Recent cache fetch failed: sessionId={}, error={}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String keyOf(Long sessionId) {
        return KEY_PREFIX + sessionId + KEY_SUFFIX;
    }
}
