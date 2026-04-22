package com.example.chat.event.dto;

import com.example.chat.event.domain.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;
import java.util.Map;

public record AppendEventRequest(
        @NotBlank String clientEventId,
        @NotBlank String userId,
        @NotNull @Positive Long sequence,
        @NotNull EventType type,
        Map<String, Object> payload,
        @NotNull LocalDateTime clientTimestamp) {
}
