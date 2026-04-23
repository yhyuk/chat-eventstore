package com.example.chat.projection.dto;

public record RebuildResult(
        Long sessionId,
        int replayedEventCount,
        Long lastSequence,
        Integer snapshotVersion   // 이벤트가 없는 세션의 경우 null
) {}
