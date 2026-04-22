package com.example.chat.session.controller;

import com.example.chat.session.dto.CreateSessionResponse;
import com.example.chat.session.dto.EndSessionResponse;
import com.example.chat.session.dto.JoinSessionRequest;
import com.example.chat.session.dto.JoinSessionResponse;
import com.example.chat.session.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionResponse createSession() {
        return sessionService.createSession();
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
