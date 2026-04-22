package com.example.chat.session.dto;

import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;

import java.time.LocalDateTime;

public record EndSessionResponse(
        Long sessionId,
        SessionStatus status,
        LocalDateTime endedAt
) {
    public static EndSessionResponse from(Session session) {
        return new EndSessionResponse(session.getId(), session.getStatus(), session.getEndedAt());
    }
}
