package com.example.chat.session.dto;

import com.example.chat.session.domain.Participant;

import java.time.LocalDateTime;

public record JoinSessionResponse(
        Long sessionId,
        String userId,
        LocalDateTime joinedAt
) {
    public static JoinSessionResponse from(Participant participant) {
        return new JoinSessionResponse(
                participant.getSession().getId(),
                participant.getUserId(),
                participant.getJoinedAt()
        );
    }
}
