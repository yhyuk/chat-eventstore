package com.example.chat.common;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Web-slice integration base. Loads full Spring context with MySQL + Redis from Testcontainers.
// RedisConfig was introduced in D3 and is now always on the classpath, so excluding auto-config no longer
// prevents Redis bean initialization -- we give the context a real Redis to keep Session REST tests working.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class AbstractWebIntegrationTest {

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
        registry.add("app.outbox.enabled", () -> "false");
    }
}
