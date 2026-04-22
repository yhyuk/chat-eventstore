package com.example.chat.realtime.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.dto.EventBroadcastFrame.EventPayload;
import com.example.chat.realtime.dto.ResumeBatchFrame;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final RecentCacheService recentCacheService;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public ResumeBatchFrame buildResumeBatch(Long sessionId, Long lastSequence) {
        List<EventPayload> cached = recentCacheService.getEventsSince(sessionId, lastSequence);
        if (!cached.isEmpty()) {
            return toFrame(cached);
        }
        // Cache miss or partial -> fall back to DB for authoritative ordering.
        List<Event> fromDb = eventRepository
                .findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(sessionId, lastSequence);
        if (fromDb.isEmpty()) {
            return new ResumeBatchFrame(Collections.emptyList(), null, null);
        }
        List<EventPayload> payloads = fromDb.stream().map(this::toPayload).toList();
        return toFrame(payloads);
    }

    public EventBroadcastFrame.EventPayload toPayload(Event event) {
        return new EventBroadcastFrame.EventPayload(
                event.getId(),
                event.getSessionId(),
                event.getSequence(),
                event.getClientEventId(),
                event.getUserId(),
                event.getType().name(),
                deserializePayload(event.getPayload()),
                event.getServerReceivedAt() == null ? null : event.getServerReceivedAt().toInstant(ZoneOffset.UTC),
                event.getClientTimestamp() == null ? null : event.getClientTimestamp().toInstant(ZoneOffset.UTC)
        );
    }

    private Map<String, Object> deserializePayload(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, PAYLOAD_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize event payload: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private ResumeBatchFrame toFrame(List<EventPayload> payloads) {
        Long from = payloads.get(0).sequence();
        Long to = payloads.get(payloads.size() - 1).sequence();
        return new ResumeBatchFrame(payloads, from, to);
    }
}
