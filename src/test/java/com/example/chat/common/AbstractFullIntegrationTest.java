package com.example.chat.common;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Full-context integration base: MySQL + Redis Testcontainers.
// Use this for WS handler, Pub/Sub, PresenceService, and event append integration tests.
// Legacy AbstractWebIntegrationTest (Redis excluded) remains for REST-only tests to avoid regression.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractFullIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.MYSQL::getUsername);
        registry.add("spring.datasource.password", SharedContainers.MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", SharedContainers.MYSQL::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.data.redis.host", SharedContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> SharedContainers.REDIS.getMappedPort(6379));
        // Tests invoke OutboxPoller.drain() deterministically; disable the scheduled trigger
        // so it does not race with test setup/teardown.
        registry.add("app.outbox.enabled", () -> "false");
    }
}
