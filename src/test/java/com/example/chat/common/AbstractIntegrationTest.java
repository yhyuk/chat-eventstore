package com.example.chat.common;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Reusable MySQL container for @DataJpaTest -- Flyway seeds schema, Hibernate validates.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedContainers.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", SharedContainers.MYSQL::getUsername);
        registry.add("spring.datasource.password", SharedContainers.MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", SharedContainers.MYSQL::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }
}
