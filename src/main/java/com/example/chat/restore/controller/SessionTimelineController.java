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

    // `at` omitted -> service substitutes LocalDateTime.now() (i.e. "current state").
    // `at` in the future is accepted as-is; semantically equivalent to "now" given no future events exist.
    @GetMapping("/{id}/timeline")
    public TimelineResponse getTimeline(
            @PathVariable Long id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return eventReplayService.restoreAt(id, at);
    }
}
