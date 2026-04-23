package com.example.chat.projection.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 스냅샷 stateJson 전용 ObjectMapper. Map 타입을 잘못 사용하더라도 키 순서가 항상 결정론적으로 유지되도록
// ORDER_MAP_ENTRIES_BY_KEYS를 강제 적용한다 — 스냅샷 회귀 테스트의 안전망.
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
