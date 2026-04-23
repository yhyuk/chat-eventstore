package com.example.chat.restore.dto;

import com.example.chat.projection.dto.SessionState.MessageState;
import com.example.chat.projection.dto.SessionState.ParticipantState;

import java.time.LocalDateTime;
import java.util.List;

// Lists (not raw Collection views) are used for the two collections so that record equals()
// is structural across identical restores -- critical for the determinism assertion.
public record TimelineResponse(
        Long sessionId,
        LocalDateTime at,
        List<ParticipantState> participants,
        List<MessageState> messages,
        boolean restoredFromSnapshot,
        Integer snapshotVersion,
        int replayedEventCount
) {
    public static TimelineResponse empty(Long sessionId, LocalDateTime at) {
        return new TimelineResponse(sessionId, at, List.of(), List.of(), false, null, 0);
    }
}
