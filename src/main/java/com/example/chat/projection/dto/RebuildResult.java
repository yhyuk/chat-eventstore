package com.example.chat.projection.dto;

public record RebuildResult(
        Long sessionId,
        int replayedEventCount,
        Long lastSequence,
        Integer snapshotVersion   // null when the session has zero events
) {}
