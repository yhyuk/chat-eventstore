package com.example.chat.event.dto;

public record AppendEventResponse(
        String status,
        Long eventId,
        Long sequence,
        Long existingEventId,
        Long existingSequence) {

    public static AppendEventResponse accepted(Long eventId, Long sequence) {
        return new AppendEventResponse("ACCEPTED", eventId, sequence, null, null);
    }

    public static AppendEventResponse duplicate(Long existingEventId, Long existingSequence) {
        return new AppendEventResponse("DUPLICATE_IGNORED", null, null, existingEventId, existingSequence);
    }
}
