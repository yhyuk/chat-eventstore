package com.example.chat.realtime.interceptor;

import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_SESSION_ID = "sessionId";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_LAST_SEQUENCE = "lastSequence";

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        Map<String, String> params = parseQuery(request.getURI());
        String rawSessionId = params.get("sessionId");
        String userId = params.get("userId");
        String rawLastSequence = params.getOrDefault("lastSequence", "0");

        if (rawSessionId == null || userId == null || userId.isBlank()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        Long sessionId;
        Long lastSequence;
        try {
            sessionId = Long.valueOf(rawSessionId);
            lastSequence = Long.valueOf(rawLastSequence);
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        Optional<Session> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        Session session = sessionOpt.get();
        if (session.isEnded()) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        if (!participantRepository.existsBySessionIdAndUserId(sessionId, userId)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_SESSION_ID, sessionId);
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_LAST_SEQUENCE, lastSequence);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        if (exception != null) {
            log.warn("Handshake completed with exception", exception);
        }
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            out.put(pair.substring(0, idx), pair.substring(idx + 1));
        }
        return out;
    }

    // Expose servlet request for tests/debugging if needed.
    @SuppressWarnings("unused")
    private boolean isServletRequest(ServerHttpRequest request) {
        return request instanceof ServletServerHttpRequest;
    }
}
