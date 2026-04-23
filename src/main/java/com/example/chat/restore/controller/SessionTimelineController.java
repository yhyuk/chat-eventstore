package com.example.chat.restore.controller;

import com.example.chat.restore.dto.TimelineResponse;
import com.example.chat.restore.service.EventReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionTimelineController {

    private final EventReplayService eventReplayService;

    // `at` 생략 시 서비스에서 LocalDateTime.now() 로 대체 (현재 상태 조회).
    // 미래 시각을 전달해도 허용하며, 미래 이벤트가 존재하지 않으므로 의미상 "현재"와 동일.
    @GetMapping("/{id}/timeline")
    public TimelineResponse getTimeline(
            @PathVariable Long id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return eventReplayService.restoreAt(id, at);
    }
}
