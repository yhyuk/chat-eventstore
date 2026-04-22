package com.example.chat.projection.dto;

import com.example.chat.projection.domain.DeadLetterEvent;

import java.time.LocalDateTime;

public record DlqEventResponse(
        Long id,
        Long originalEventId,
        Long sessionId,
        Long sequence,
        String eventType,
        String errorMessage,
        Integer retryCount,
        LocalDateTime movedAt) {

    public static DlqEventResponse from(DeadLetterEvent entity) {
        return new DlqEventResponse(
                entity.getId(),
                entity.getOriginalEventId(),
                entity.getSessionId(),
                entity.getSequence(),
                entity.getEventType(),
                entity.getErrorMessage(),
                entity.getRetryCount(),
                entity.getMovedAt()
        );
    }
}
