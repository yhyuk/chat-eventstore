package com.example.chat.session.dto;

import com.example.chat.session.domain.Participant;

import java.time.LocalDateTime;

// 클라이언트가 첫 메시지의 sequence를 안전하게 채번하려면 join 시점의 last_sequence를 알아야 한다.
// SessionService.joinSession이 JOIN 이벤트를 sequence=last_sequence+1로 자동 append하므로,
// 응답에 그 직후의 last_sequence를 실어 보내면 클라이언트는 lastSequence+1부터 메시지를 시작할 수 있다.
public record JoinSessionResponse(
        Long sessionId,
        String userId,
        LocalDateTime joinedAt,
        Long lastSequence
) {
    // JPQL UPDATE(updateLastSequence) + clearAutomatically=true 영향으로 participant.getSession()의
    // lastSequence가 영속성 컨텍스트 비움 시점에 stale일 수 있다.
    // 호출자가 정확한 lastSequence를 알고 있을 때는 of(...)로 직접 전달한다.
    public static JoinSessionResponse from(Participant participant) {
        return new JoinSessionResponse(
                participant.getSession().getId(),
                participant.getUserId(),
                participant.getJoinedAt(),
                participant.getSession().getLastSequence()
        );
    }

    public static JoinSessionResponse of(Participant participant, Long lastSequence) {
        return new JoinSessionResponse(
                participant.getSession().getId(),
                participant.getUserId(),
                participant.getJoinedAt(),
                lastSequence
        );
    }
}
