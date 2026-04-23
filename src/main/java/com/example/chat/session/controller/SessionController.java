package com.example.chat.session.controller;

import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.dto.CreateSessionResponse;
import com.example.chat.session.dto.EndSessionResponse;
import com.example.chat.session.dto.JoinSessionRequest;
import com.example.chat.session.dto.JoinSessionResponse;
import com.example.chat.session.dto.SessionListResponse;
import com.example.chat.session.service.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
@Validated
public class SessionController {

    private final SessionService sessionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionResponse createSession() {
        return sessionService.createSession();
    }

    @GetMapping
    public SessionListResponse listSessions(
            @RequestParam(required = false) SessionStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) String participant,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return sessionService.listSessions(status, from, to, participant, page, size);
    }

    @PostMapping("/{sessionId}/join")
    public ResponseEntity<JoinSessionResponse> joinSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody JoinSessionRequest request) {
        return ResponseEntity.ok(sessionService.joinSession(sessionId, request.userId()));
    }

    @PostMapping("/{sessionId}/end")
    public ResponseEntity<EndSessionResponse> endSession(@PathVariable Long sessionId) {
        return ResponseEntity.ok(sessionService.endSession(sessionId));
    }
}
