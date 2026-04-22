package com.example.chat.session.dto;

import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;

import java.time.LocalDateTime;

public record CreateSessionResponse(
        Long sessionId,
        SessionStatus status,
        LocalDateTime createdAt
) {
    public static CreateSessionResponse from(Session session) {
        return new CreateSessionResponse(session.getId(), session.getStatus(), session.getCreatedAt());
    }
}
