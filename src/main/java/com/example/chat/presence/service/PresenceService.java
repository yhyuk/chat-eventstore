package com.example.chat.presence.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PresenceService {

    private static final String KEY_PREFIX = "presence:session:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public PresenceService(StringRedisTemplate redisTemplate,
                           @Value("${app.presence.ttl-seconds:30}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    public void goOnline(Long sessionId, String userId) {
        String key = keyOf(sessionId);
        try {
            // Pipeline SADD + EXPIRE atomically from the client's perspective: single round-trip, both commands queued.
            redisTemplate.executePipelined((RedisConnection connection) -> {
                connection.sAdd(key.getBytes(StandardCharsets.UTF_8), userId.getBytes(StandardCharsets.UTF_8));
                connection.expire(key.getBytes(StandardCharsets.UTF_8), ttlSeconds);
                return null;
            });
        } catch (Exception e) {
            log.warn("goOnline failed: sessionId={}, userId={}, error={}", sessionId, userId, e.getMessage());
        }
    }

    public void refreshTtl(Long sessionId, String userId) {
        // Called per inbound message to keep an active user from timing out.
        // userId is accepted for API symmetry; EXPIRE applies to the whole session key.
        String key = keyOf(sessionId);
        try {
            redisTemplate.expire(key, java.time.Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("refreshTtl failed: sessionId={}, userId={}, error={}", sessionId, userId, e.getMessage());
        }
    }

    public void goOffline(Long sessionId, String userId) {
        String key = keyOf(sessionId);
        try {
            redisTemplate.opsForSet().remove(key, userId);
        } catch (Exception e) {
            log.warn("goOffline failed: sessionId={}, userId={}, error={}", sessionId, userId, e.getMessage());
        }
    }

    public Set<String> getOnlineUsers(Long sessionId) {
        try {
            Set<String> members = redisTemplate.opsForSet().members(keyOf(sessionId));
            return members == null ? Collections.emptySet() : members;
        } catch (Exception e) {
            log.warn("getOnlineUsers failed: sessionId={}, error={}", sessionId, e.getMessage());
            return Collections.emptySet();
        }
    }

    private String keyOf(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
