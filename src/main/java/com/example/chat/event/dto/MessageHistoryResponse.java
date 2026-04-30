package com.example.chat.event.dto;

import com.example.chat.realtime.dto.EventBroadcastFrame.EventPayload;

import java.util.List;

/**
 * 채팅 히스토리 페이지네이션 응답.
 *
 * <p>커서 기반 페이지네이션을 사용한다.
 * <ul>
 *   <li>{@code messages} — 최신순(sequence DESC) 정렬된 이벤트 목록.
 *       UI에서 위→아래로 그릴 때 reverse하여 사용.</li>
 *   <li>{@code nextBefore} — 다음 페이지 요청 시 사용할 커서.
 *       이번 응답의 가장 오래된 sequence 값. {@code hasMore=false}면 null.</li>
 *   <li>{@code hasMore} — 더 받을 페이지가 있는지 여부.
 *       limit+1 조회로 판정한다.</li>
 * </ul>
 */
public record MessageHistoryResponse(
        List<EventPayload> messages,
        Long nextBefore,
        boolean hasMore
) {
    public static MessageHistoryResponse empty() {
        return new MessageHistoryResponse(List.of(), null, false);
    }
}
