package com.example.chat.event.service;

import com.example.chat.common.exception.DuplicateEventException;
import com.example.chat.common.exception.InvalidSequenceException;
import com.example.chat.common.metrics.ChatMetrics;
import com.example.chat.event.domain.Event;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
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
    private final ChatMetrics chatMetrics;

    // Injected via @PersistenceContext so the EntityManager is tx-scoped and shares the
    // writeTemplate's transaction. Using em.persist(...) explicitly forces INSERT semantics.
    // Without this, JpaRepository.saveAndFlush() on an entity whose @IdClass fields are
    // pre-populated (as Event is) goes through em.merge(...) and can silently UPDATE an
    // existing row -- bypassing the UK(session_id, client_event_id) constraint that the
    // append path relies on for duplicate detection.
    @PersistenceContext
    private EntityManager entityManager;

    public EventAppendService(
            EventRepository eventRepository,
            SessionRepository sessionRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            ChatMetrics chatMetrics) {
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
        this.chatMetrics = chatMetrics;
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
                // persist() forces INSERT semantics (unlike save()/saveAndFlush() which use merge
                // when @IdClass fields are pre-set and may silently UPDATE, masking UK violations).
                entityManager.persist(event);
                entityManager.flush();
                sessionRepository.updateLastSequence(sessionId, request.sequence());
                return event;
            });
            chatMetrics.incEventsReceived(request.type(), "ACCEPTED");
            return AppendResult.accepted(saved);
        } catch (DuplicateEventException dup) {
            chatMetrics.incEventsReceived(request.type(), "DUPLICATE_IGNORED");
            chatMetrics.incDuplicates();
            throw dup;
        } catch (DataIntegrityViolationException ex) {
            return resolveConflict(sessionId, request, ex);
        } catch (PersistenceException ex) {
            // em.persist()+em.flush() is not wrapped by Spring Data proxies, so raw
            // Hibernate ConstraintViolationException is thrown. Translate to the same
            // conflict resolution path.
            if (ex.getCause() instanceof ConstraintViolationException
                    || ex instanceof ConstraintViolationException) {
                return resolveConflict(sessionId, request,
                        new DataIntegrityViolationException("Persist failed", ex));
            }
            throw ex;
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
                    // Called from within catch(DataIntegrityViolationException); the outer
                    // catch(DuplicateEventException) is NOT re-entered, so bump metrics here.
                    chatMetrics.incEventsReceived(request.type(), "DUPLICATE_IGNORED");
                    chatMetrics.incDuplicates();
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
                chatMetrics.incEventsReceived(request.type(), "DUPLICATE_IGNORED");
                chatMetrics.incDuplicates();
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
