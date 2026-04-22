package com.example.chat.event.controller;

import com.example.chat.common.exception.DuplicateEventException;
import com.example.chat.common.exception.SessionEndedException;
import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendEventResponse;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.pubsub.RedisMessagePublisher;
import com.example.chat.realtime.service.RecentCacheService;
import com.example.chat.realtime.service.ResumeService;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.SessionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionEventController {

    private final SessionRepository sessionRepository;
    private final EventAppendService eventAppendService;
    private final ResumeService resumeService;
    private final RecentCacheService recentCacheService;
    private final RedisMessagePublisher publisher;

    @PostMapping("/{sessionId}/events")
    public ResponseEntity<AppendEventResponse> append(
            @PathVariable Long sessionId,
            @Valid @RequestBody AppendEventRequest request) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (session.isEnded()) {
            throw new SessionEndedException(sessionId);
        }
        try {
            AppendResult result = eventAppendService.append(sessionId, request);
            Event saved = result.savedEvent();
            EventBroadcastFrame.EventPayload payload = resumeService.toPayload(saved);
            publisher.publish(sessionId, new EventBroadcastFrame(payload));
            recentCacheService.append(sessionId, saved.getSequence(), payload);
            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(AppendEventResponse.accepted(saved.getId(), saved.getSequence()));
        } catch (DuplicateEventException dup) {
            return ResponseEntity
                    .ok(AppendEventResponse.duplicate(dup.getExistingEventId(), dup.getExistingSequence()));
        }
    }
}
