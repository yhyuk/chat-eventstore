package com.example.chat.restore.dto;

import com.example.chat.projection.dto.SessionState.MessageState;
import com.example.chat.projection.dto.SessionState.ParticipantState;

import java.time.LocalDateTime;
import java.util.List;

// 두 컬렉션을 List로 선언하는 이유: record의 equals()가 구조적으로 동작해야
// 동일 복원 결과의 결정론(determinism) 검증이 가능하기 때문.
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
