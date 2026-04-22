package com.example.chat.projection.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Dedicated ObjectMapper for snapshot stateJson so key ordering stays deterministic even if
// callers accidentally swap the Map type -- belt-and-braces for snapshot regression safety.
@Configuration
public class SnapshotObjectMapperConfig {

    public static final String QUALIFIER = "snapshotObjectMapper";

    @Bean(QUALIFIER)
    public ObjectMapper snapshotObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return mapper;
    }
}
