package com.example.chat.common;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

public final class SharedContainers {

    public static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("chat_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withReuse(true);

    static {
        MYSQL.start();
        REDIS.start();
    }

    private SharedContainers() {
    }
}
