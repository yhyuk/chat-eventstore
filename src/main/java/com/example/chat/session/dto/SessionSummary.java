package com.example.chat.session.dto;

import com.example.chat.session.domain.SessionStatus;

import java.time.LocalDateTime;

public record SessionSummary(
        Long id,
        SessionStatus status,
        LocalDateTime createdAt,
        LocalDateTime endedAt,
        Long lastSequence
) {}
