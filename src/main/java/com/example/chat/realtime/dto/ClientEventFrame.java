package com.example.chat.realtime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.Map;

public record ClientEventFrame(
        @NotBlank String clientEventId,
        @NotNull @Positive Long sequence,
        @NotBlank String type,
        Map<String, Object> payload,
        @NotNull Instant clientTimestamp,
        @NotBlank String userId) {
}
