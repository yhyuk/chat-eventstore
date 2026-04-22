package com.example.chat.event.service;

import com.example.chat.common.exception.DuplicateEventException;
import com.example.chat.common.exception.InvalidSequenceException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class EventAppendService {

    private final EventRepository eventRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate readOnlyTemplate;

    public EventAppendService(
            EventRepository eventRepository,
            SessionRepository sessionRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
    }

    public AppendResult append(Long sessionId, AppendEventRequest request) {
        // Fail-fast: sequence must be strictly positive. Matches ClientEventFrame @Positive validation.
        if (request.sequence() == null || request.sequence() <= 0) {
            throw new InvalidSequenceException(sessionId, request.sequence());
        }

        String payloadJson = serializePayload(request.payload());

        try {
            // Primary write: INSERT + UPDATE last_sequence in one transaction.
            // Throws DataIntegrityViolationException on UNIQUE/PK conflict, rollback guaranteed.
            Event saved = writeTemplate.execute(status -> {
                Event event = Event.builder()
                        .sessionId(sessionId)
                        .sequence(request.sequence())
                        .clientEventId(request.clientEventId())
                        .userId(request.userId())
                        .type(request.type())
                        .payload(payloadJson)
                        .clientTimestamp(request.clientTimestamp())
                        .build();
                // @DynamicInsert omits null fields (projectionStatus, retryCount, nextRetryAt,
                // lastError, id, serverReceivedAt) so DB DEFAULT and @Generated(INSERT) apply.
                Event persisted = eventRepository.saveAndFlush(event);
                sessionRepository.updateLastSequence(sessionId, request.sequence());
                return persisted;
            });
            return AppendResult.accepted(saved);
        } catch (DataIntegrityViolationException ex) {
            return resolveConflict(sessionId, request, ex);
        }
    }

    private AppendResult resolveConflict(Long sessionId, AppendEventRequest request,
                                         DataIntegrityViolationException original) {
        return readOnlyTemplate.execute(status -> {
            // Step 1: PK conflict (session_id, sequence)
            Optional<Event> bySeq = eventRepository.findBySessionIdAndSequence(sessionId, request.sequence());
            if (bySeq.isPresent()) {
                Event existing = bySeq.get();
                if (existing.getClientEventId().equals(request.clientEventId())) {
                    throw new DuplicateEventException(existing.getId(), existing.getSequence());
                }
                // Different clientEventId at same sequence -> real conflict.
                throw new InvalidSequenceException(sessionId, request.sequence());
            }
            // Step 2: UK conflict (session_id, client_event_id) at different sequence
            Optional<Event> byClientId = eventRepository.findBySessionIdAndClientEventId(
                    sessionId, request.clientEventId());
            if (byClientId.isPresent()) {
                Event existing = byClientId.get();
                throw new DuplicateEventException(existing.getId(), existing.getSequence());
            }
            // Step 3: neither key conflicts — constraint other than (PK, UK) fired. Rethrow original.
            log.warn("Unexpected DataIntegrityViolation without matching PK/UK row: sessionId={}, sequence={}",
                    sessionId, request.sequence());
            throw original;
        });
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return payload == null ? "{}" : objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid event payload", e);
        }
    }
}
