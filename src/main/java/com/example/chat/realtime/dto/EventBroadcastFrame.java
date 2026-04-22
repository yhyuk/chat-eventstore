package com.example.chat.realtime.dto;

import java.time.Instant;
import java.util.Map;

public record EventBroadcastFrame(EventPayload event) {

    public String frameType() {
        return "EVENT";
    }

    public record EventPayload(
            Long id,
            Long sessionId,
            Long sequence,
            String clientEventId,
            String userId,
            String type,
            Map<String, Object> payload,
            Instant serverReceivedAt,
            Instant clientTimestamp) {
    }
}
