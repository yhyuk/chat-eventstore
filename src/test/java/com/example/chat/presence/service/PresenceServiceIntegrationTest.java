package com.example.chat.presence.service;

import com.example.chat.common.AbstractFullIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PresenceServiceIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flushRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void goOnline_adds_member_and_sets_ttl() {
        Long sessionId = 42L;
        presenceService.goOnline(sessionId, "alice");

        Set<String> online = presenceService.getOnlineUsers(sessionId);
        assertThat(online).containsExactly("alice");

        Long ttl = redisTemplate.getExpire("presence:session:" + sessionId);
        assertThat(ttl).isGreaterThan(0L);
    }

    @Test
    void goOffline_removes_member() {
        Long sessionId = 42L;
        presenceService.goOnline(sessionId, "alice");
        presenceService.goOnline(sessionId, "bob");

        presenceService.goOffline(sessionId, "alice");

        assertThat(presenceService.getOnlineUsers(sessionId)).containsExactly("bob");
    }

    @Test
    void refreshTtl_extends_expire() {
        Long sessionId = 42L;
        presenceService.goOnline(sessionId, "alice");
        String key = "presence:session:" + sessionId;

        // Artificially shorten TTL, then refresh should reset it back to default (30s by yml default).
        redisTemplate.expire(key, Duration.ofSeconds(1));
        Long shortened = redisTemplate.getExpire(key);
        assertThat(shortened).isLessThanOrEqualTo(1L);

        presenceService.refreshTtl(sessionId, "alice");
        Long refreshed = redisTemplate.getExpire(key);
        assertThat(refreshed).isGreaterThan(shortened);
    }

    @Test
    void ttl_expires_when_not_refreshed() {
        Long sessionId = 42L;
        presenceService.goOnline(sessionId, "alice");
        String key = "presence:session:" + sessionId;
        redisTemplate.expire(key, Duration.ofSeconds(1));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(redisTemplate.hasKey(key)).isFalse()
        );
    }
}
