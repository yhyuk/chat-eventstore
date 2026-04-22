package com.example.chat.projection.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.repository.SessionProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

// Applies a single event to the read-model counters. No persistence-context branching --
// the single upsert below handles both first-INSERT and idempotent UPDATE paths.
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectionService {

    private final SessionProjectionRepository repository;

    public void apply(Event event) {
        Optional<SessionProjection> current = repository.findById(event.getSessionId());
        int participantCount = current.map(SessionProjection::getParticipantCount).orElse(0);
        long messageCount = current.map(SessionProjection::getMessageCount).orElse(0L);
        LocalDateTime lastMessageAt = current.map(SessionProjection::getLastMessageAt).orElse(null);

        EventType type = event.getType();
        switch (type) {
            case MESSAGE -> {
                messageCount += 1;
                lastMessageAt = event.getServerReceivedAt();
            }
            case JOIN -> participantCount += 1;
            case LEAVE -> participantCount = Math.max(0, participantCount - 1);
            case DISCONNECT, RECONNECT, EDIT, DELETE -> {
                // Tombstones and presence-only events do not move aggregate counters.
            }
        }

        // last_applied_event_id guards double-apply: the upsert only overwrites counters
        // when the incoming event id is greater than what is already stored.
        repository.upsertProjection(
                event.getSessionId(),
                participantCount,
                messageCount,
                lastMessageAt,
                event.getId()
        );
    }
}
