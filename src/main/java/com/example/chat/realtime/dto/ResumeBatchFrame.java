package com.example.chat.realtime.dto;

import java.util.List;

public record ResumeBatchFrame(
        List<EventBroadcastFrame.EventPayload> events,
        Long fromSequence,
        Long toSequence) {

    public String frameType() {
        return "RESUME_BATCH";
    }
}
