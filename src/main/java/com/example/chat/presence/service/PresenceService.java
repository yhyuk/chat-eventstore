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
            // SADD + EXPIRE를 파이프라인으로 묶어 단일 왕복으로 처리 (클라이언트 관점에서 원자적).
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
        // 인바운드 메시지마다 호출해 활성 사용자의 TTL을 갱신.
        // userId는 API 대칭성을 위해 받지만, EXPIRE는 세션 키 전체에 적용됨.
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
