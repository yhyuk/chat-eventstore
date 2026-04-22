package com.example.chat.event.dto;

import com.example.chat.event.domain.Event;

public record AppendResult(
        Status status,
        Event savedEvent,
        Long existingEventId,
        Long existingSequence) {

    public enum Status { ACCEPTED, DUPLICATE_IGNORED }

    public static AppendResult accepted(Event saved) {
        return new AppendResult(Status.ACCEPTED, saved, null, null);
    }

    public static AppendResult duplicate(Long existingEventId, Long existingSequence) {
        return new AppendResult(Status.DUPLICATE_IGNORED, null, existingEventId, existingSequence);
    }
}
