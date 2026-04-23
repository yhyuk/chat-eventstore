package com.example.chat.realtime.handler;

import com.example.chat.common.exception.DuplicateEventException;
import com.example.chat.common.exception.ErrorCode;
import com.example.chat.common.exception.InvalidSequenceException;
import com.example.chat.common.metrics.ChatMetrics;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.presence.service.PresenceService;
import com.example.chat.realtime.dto.AckFrame;
import com.example.chat.realtime.dto.ClientEventFrame;
import com.example.chat.realtime.dto.ErrorFrame;
import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.dto.ResumeBatchFrame;
import com.example.chat.realtime.interceptor.ChatHandshakeInterceptor;
import com.example.chat.realtime.pubsub.RedisMessagePublisher;
import com.example.chat.realtime.registry.SessionRegistry;
import com.example.chat.realtime.service.RecentCacheService;
import com.example.chat.realtime.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final EventAppendService eventAppendService;
    private final SessionRegistry sessionRegistry;
    private final PresenceService presenceService;
    private final RecentCacheService recentCacheService;
    private final RedisMessagePublisher publisher;
    private final ResumeService resumeService;
    private final ChatMetrics chatMetrics;

    @PostConstruct
    public void registerGauges() {
        chatMetrics.registerWsSessionsGauge(sessionRegistry::sessionCount);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
        Long sessionId = (Long) webSocketSession.getAttributes().get(ChatHandshakeInterceptor.ATTR_SESSION_ID);
        String userId = (String) webSocketSession.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        Long lastSequence = (Long) webSocketSession.getAttributes().get(ChatHandshakeInterceptor.ATTR_LAST_SEQUENCE);

        sessionRegistry.register(sessionId, webSocketSession);
        presenceService.goOnline(sessionId, userId);

        if (lastSequence != null && lastSequence > 0) {
            ResumeBatchFrame batch = resumeService.buildResumeBatch(sessionId, lastSequence);
            if (!batch.events().isEmpty()) {
                sendFrame(webSocketSession, batch, batch.frameType());
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocketSession, TextMessage message) throws Exception {
        Long sessionId = (Long) webSocketSession.getAttributes().get(ChatHandshakeInterceptor.ATTR_SESSION_ID);
        String userId = (String) webSocketSession.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);

        ClientEventFrame frame;
        try {
            frame = objectMapper.readValue(message.getPayload(), ClientEventFrame.class);
        } catch (Exception e) {
            sendError(webSocketSession, ErrorCode.INVALID_REQUEST.name(), "Malformed frame: " + e.getMessage());
            return;
        }

        EventType eventType;
        try {
            eventType = EventType.valueOf(frame.type());
        } catch (IllegalArgumentException e) {
            sendError(webSocketSession, ErrorCode.INVALID_REQUEST.name(), "Unknown event type: " + frame.type());
            return;
        }

        AppendEventRequest request = new AppendEventRequest(
                frame.clientEventId(),
                userId != null ? userId : frame.userId(),
                frame.sequence(),
                eventType,
                frame.payload(),
                frame.clientTimestamp() == null
                        ? LocalDateTime.now()
                        : LocalDateTime.ofInstant(frame.clientTimestamp(), ZoneOffset.UTC)
        );

        try {
            AppendResult result = eventAppendService.append(sessionId, request);
            Event saved = result.savedEvent();
            EventBroadcastFrame.EventPayload payload = resumeService.toPayload(saved);
            EventBroadcastFrame broadcast = new EventBroadcastFrame(payload);

            publisher.publish(sessionId, broadcast);
            recentCacheService.append(sessionId, saved.getSequence(), payload);

            sendFrame(webSocketSession, AckFrame.accepted(frame.clientEventId()), "ACK");
            presenceService.refreshTtl(sessionId, userId);
        } catch (DuplicateEventException dup) {
            sendFrame(webSocketSession,
                    AckFrame.duplicate(frame.clientEventId(), dup.getExistingEventId(), dup.getExistingSequence()),
                    "ACK");
            presenceService.refreshTtl(sessionId, userId);
        } catch (InvalidSequenceException seqEx) {
            sendError(webSocketSession, ErrorCode.INVALID_SEQUENCE.name(), seqEx.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected append failure: sessionId={}, error={}", sessionId, e.getMessage(), e);
            sendError(webSocketSession, ErrorCode.INTERNAL_ERROR.name(), "Internal error");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus status) throws Exception {
        Long sessionId = (Long) webSocketSession.getAttributes().get(ChatHandshakeInterceptor.ATTR_SESSION_ID);
        String userId = (String) webSocketSession.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        if (sessionId != null) {
            sessionRegistry.unregister(sessionId, webSocketSession);
        }
        if (sessionId != null && userId != null) {
            presenceService.goOffline(sessionId, userId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession webSocketSession, Throwable exception) {
        log.warn("Transport error: wsId={}, error={}", webSocketSession.getId(), exception.getMessage());
        try {
            if (webSocketSession.isOpen()) {
                webSocketSession.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException closeError) {
            log.debug("Failed to close session after transport error: {}", closeError.getMessage());
        }
    }

    private void sendFrame(WebSocketSession webSocketSession, Object frameBody, String frameType) {
        try {
            String json = objectMapper.writeValueAsString(new FrameEnvelope(frameType, frameBody));
            synchronized (webSocketSession) {
                if (webSocketSession.isOpen()) {
                    webSocketSession.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.warn("Send frame failed: wsId={}, frameType={}, error={}",
                    webSocketSession.getId(), frameType, e.getMessage());
        }
    }

    private void sendError(WebSocketSession webSocketSession, String code, String message) {
        sendFrame(webSocketSession, new ErrorFrame(code, message), "ERROR");
    }

    // Jackson 직렬화 결과: { "frameType": "...", "body": {...} }.
    private record FrameEnvelope(String frameType, Object body) {
    }
}
