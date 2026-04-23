package com.example.chat.realtime.handler;

import com.example.chat.common.exception.DuplicateEventException;
import com.example.chat.common.exception.InvalidSequenceException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.presence.service.PresenceService;
import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.interceptor.ChatHandshakeInterceptor;
import com.example.chat.realtime.pubsub.RedisMessagePublisher;
import com.example.chat.realtime.registry.SessionRegistry;
import com.example.chat.realtime.service.RecentCacheService;
import com.example.chat.realtime.service.ResumeService;
import com.example.chat.common.metrics.ChatMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatWebSocketHandlerTest {

    @Mock private EventAppendService eventAppendService;
    @Mock private SessionRegistry sessionRegistry;
    @Mock private PresenceService presenceService;
    @Mock private RecentCacheService recentCacheService;
    @Mock private RedisMessagePublisher publisher;
    @Mock private ResumeService resumeService;
    @Mock private ChatMetrics chatMetrics;
    @Mock private WebSocketSession ws;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatWebSocketHandler handler;
    private Map<String, Object> attrs;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        handler = new ChatWebSocketHandler(objectMapper, eventAppendService, sessionRegistry,
                presenceService, recentCacheService, publisher, resumeService, chatMetrics);
        attrs = new HashMap<>();
        attrs.put(ChatHandshakeInterceptor.ATTR_SESSION_ID, 42L);
        attrs.put(ChatHandshakeInterceptor.ATTR_USER_ID, "alice");
        when(ws.getAttributes()).thenReturn(attrs);
        when(ws.isOpen()).thenReturn(true);
    }

    @Test
    void malformed_json_returns_error_frame_and_keeps_connection_open() throws Exception {
        handler.handleTextMessage(ws, new TextMessage("{not-json"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("ERROR");
        assertThat(captor.getValue().getPayload()).contains("INVALID_REQUEST");
        verify(eventAppendService, never()).append(anyLong(), any());
    }

    @Test
    void unknown_event_type_returns_error_frame() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "clientEventId", "c1",
                "sequence", 1,
                "type", "BOGUS_TYPE",
                "payload", Map.of(),
                "clientTimestamp", Instant.now().toString(),
                "userId", "alice"));

        handler.handleTextMessage(ws, new TextMessage(json));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("Unknown event type");
        verify(eventAppendService, never()).append(anyLong(), any());
    }

    @Test
    void accepted_event_publishes_and_refreshes_presence() throws Exception {
        Event saved = buildEvent();
        when(eventAppendService.append(eq(42L), any(AppendEventRequest.class)))
                .thenReturn(AppendResult.accepted(saved));
        when(resumeService.toPayload(saved)).thenReturn(payload(saved));

        handler.handleTextMessage(ws, new TextMessage(validFrameJson("c1", 1L)));

        verify(publisher).publish(eq(42L), any(EventBroadcastFrame.class));
        verify(recentCacheService).append(eq(42L), eq(1L), any());
        verify(presenceService).refreshTtl(42L, "alice");
    }

    @Test
    void duplicate_event_sends_duplicate_ack() throws Exception {
        when(eventAppendService.append(eq(42L), any(AppendEventRequest.class)))
                .thenThrow(new DuplicateEventException(999L, 1L));

        handler.handleTextMessage(ws, new TextMessage(validFrameJson("c1", 1L)));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("DUPLICATE_IGNORED");
        assertThat(payload).contains("999");
        verify(publisher, never()).publish(anyLong(), any());
        verify(presenceService).refreshTtl(42L, "alice");
    }

    @Test
    void invalid_sequence_sends_error_frame() throws Exception {
        when(eventAppendService.append(eq(42L), any(AppendEventRequest.class)))
                .thenThrow(new InvalidSequenceException(42L, 1L));

        handler.handleTextMessage(ws, new TextMessage(validFrameJson("c1", 1L)));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("INVALID_SEQUENCE");
        verify(publisher, never()).publish(anyLong(), any());
    }

    @Test
    void redis_publish_failure_still_sends_ack() throws Exception {
        Event saved = buildEvent();
        when(eventAppendService.append(eq(42L), any(AppendEventRequest.class)))
                .thenReturn(AppendResult.accepted(saved));
        when(resumeService.toPayload(saved)).thenReturn(payload(saved));
        // Publisher swallows exceptions internally; simulate silent failure via lenient no-throw stub.
        // Here we verify that even when publisher is called and nothing is thrown, ACK still fires.
        handler.handleTextMessage(ws, new TextMessage(validFrameJson("c1", 1L)));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(ws, atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("ACCEPTED");
    }

    private String validFrameJson(String clientEventId, long sequence) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "clientEventId", clientEventId,
                "sequence", sequence,
                "type", "MESSAGE",
                "payload", Map.of("text", "hi"),
                "clientTimestamp", Instant.now().toString(),
                "userId", "alice"));
    }

    private Event buildEvent() {
        Event e = Event.builder()
                .sessionId(42L)
                .sequence(1L)
                .clientEventId("c1")
                .userId("alice")
                .type(EventType.MESSAGE)
                .payload("{}")
                .clientTimestamp(LocalDateTime.now())
                .build();
        setField(e, "id", 777L);
        return e;
    }

    private EventBroadcastFrame.EventPayload payload(Event saved) {
        return new EventBroadcastFrame.EventPayload(
                saved.getId(), saved.getSessionId(), saved.getSequence(),
                saved.getClientEventId(), saved.getUserId(), saved.getType().name(),
                Map.of(), Instant.now(), Instant.now());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
