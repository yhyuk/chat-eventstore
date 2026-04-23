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

    // @IdClass 필드가 미리 채워진 Event는 JpaRepository.save()가 em.merge()로 동작해
    // 기존 행을 조용히 UPDATE할 수 있다. em.persist()를 직접 호출해 INSERT 의미론을 강제하고,
    // UK(session_id, client_event_id) 제약 위반을 통한 중복 감지 경로가 정상 작동하도록 한다.
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
        if (request.sequence() == null || request.sequence() <= 0) {
            throw new InvalidSequenceException(sessionId, request.sequence());
        }

        String payloadJson = serializePayload(request.payload());

        try {
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
            // em.persist()+em.flush()는 Spring Data 프록시로 감싸지지 않아 Hibernate
            // ConstraintViolationException이 직접 던져진다. 동일한 충돌 해소 경로로 변환한다.
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
            // 1단계: PK(session_id, sequence) 충돌 확인
            Optional<Event> bySeq = eventRepository.findBySessionIdAndSequence(sessionId, request.sequence());
            if (bySeq.isPresent()) {
                Event existing = bySeq.get();
                if (existing.getClientEventId().equals(request.clientEventId())) {
                    // catch(DataIntegrityViolationException) 내부에서 호출되므로 외부 catch(DuplicateEventException)로 재진입하지 않는다.
                    // 메트릭을 여기서 직접 증가시킨다.
                    chatMetrics.incEventsReceived(request.type(), "DUPLICATE_IGNORED");
                    chatMetrics.incDuplicates();
                    throw new DuplicateEventException(existing.getId(), existing.getSequence());
                }
                // 같은 sequence에 다른 clientEventId -> 실제 순서 충돌
                throw new InvalidSequenceException(sessionId, request.sequence());
            }
            // 2단계: UK(session_id, client_event_id) 충돌 — 다른 sequence로 재전송된 중복 이벤트
            Optional<Event> byClientId = eventRepository.findBySessionIdAndClientEventId(
                    sessionId, request.clientEventId());
            if (byClientId.isPresent()) {
                Event existing = byClientId.get();
                chatMetrics.incEventsReceived(request.type(), "DUPLICATE_IGNORED");
                chatMetrics.incDuplicates();
                throw new DuplicateEventException(existing.getId(), existing.getSequence());
            }
            // 3단계: PK/UK 이외의 제약 위반 — 예상치 못한 오류이므로 원본 예외를 다시 던진다.
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
