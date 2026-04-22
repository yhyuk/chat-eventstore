package com.example.chat.realtime.registry;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SessionRegistry {

    private final ConcurrentHashMap<Long, CopyOnWriteArraySet<WebSocketSession>> sessionMap = new ConcurrentHashMap<>();

    public void register(Long sessionId, WebSocketSession webSocketSession) {
        sessionMap.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(webSocketSession);
    }

    public void unregister(Long sessionId, WebSocketSession webSocketSession) {
        CopyOnWriteArraySet<WebSocketSession> sessions = sessionMap.get(sessionId);
        if (sessions != null) {
            sessions.remove(webSocketSession);
            if (sessions.isEmpty()) {
                sessionMap.remove(sessionId, sessions);
            }
        }
    }

    public Set<WebSocketSession> getSessions(Long sessionId) {
        CopyOnWriteArraySet<WebSocketSession> sessions = sessionMap.get(sessionId);
        return sessions == null ? Collections.emptySet() : sessions;
    }
}
