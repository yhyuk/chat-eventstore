package com.example.chat.event.controller;

import com.example.chat.event.dto.MessageHistoryResponse;
import com.example.chat.event.service.MessageHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 채팅 히스토리 조회 API.
 *
 * <p>WebSocket의 ResumeBatch는 "재접속 시 누락 이벤트 보충" 용도이므로,
 * 신규 진입 / 위로 스크롤 같은 일반적인 히스토리 조회는 본 API를 사용한다.
 *
 * <p>커서 기반 페이지네이션을 사용한다.
 * <pre>
 *   첫 진입:  GET /sessions/42/messages
 *   다음 페이지: GET /sessions/42/messages?before={응답.nextBefore}
 * </pre>
 */
@RestController
@RequestMapping("/sessions/{sessionId}/messages")
@RequiredArgsConstructor
public class MessageHistoryController {

    private final MessageHistoryService messageHistoryService;

    @GetMapping
    public MessageHistoryResponse getHistory(
            @PathVariable Long sessionId,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "50") int limit) {
        return messageHistoryService.getHistory(sessionId, before, limit);
    }
}
