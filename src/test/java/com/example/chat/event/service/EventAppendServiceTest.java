package com.example.chat.event.service;

import com.example.chat.common.exception.DuplicateEventException;
import com.example.chat.common.exception.InvalidSequenceException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.common.metrics.ChatMetrics;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventAppendServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ChatMetrics chatMetrics;

    private EventAppendService service;

    @BeforeEach
    void setUp() {
        // Let TransactionTemplate execute its callback inline — no real JDBC txn required for mocked repositories.
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        service = new EventAppendService(eventRepository, sessionRepository, new ObjectMapper(), transactionManager, chatMetrics);
        // Inject the @PersistenceContext mock via reflection since Mockito cannot see the field annotation.
        setField(service, "entityManager", entityManager);
    }

    @Test
    void append_rejects_zero_sequence() {
        AppendEventRequest request = request(0L, "c1");
        assertThatThrownBy(() -> service.append(1L, request))
                .isInstanceOf(InvalidSequenceException.class);
    }

    @Test
    void append_rejects_negative_sequence() {
        AppendEventRequest request = request(-5L, "c1");
        assertThatThrownBy(() -> service.append(1L, request))
                .isInstanceOf(InvalidSequenceException.class);
    }

    @Test
    void append_case1_new_sequence_inserts_successfully() {
        AppendEventRequest request = request(10L, "c1");
        // persist()+flush() are void -- simulate the managed entity with a stub id via reflection
        // once persist() is invoked, so the returned Event carries the same id the DB would assign.
        doAnswer(inv -> {
            setField(inv.getArgument(0), "id", 100L);
            return null;
        }).when(entityManager).persist(any(Event.class));
        doNothing().when(entityManager).flush();

        AppendResult result = service.append(1L, request);

        assertThat(result.status()).isEqualTo(AppendResult.Status.ACCEPTED);
        assertThat(result.savedEvent().getId()).isEqualTo(100L);
        verify(sessionRepository).updateLastSequence(1L, 10L);
    }

    @Test
    void append_case2_duplicate_same_clientId_returns_duplicate() {
        AppendEventRequest request = request(10L, "c1");
        Event existing = event(99L, 10L, "c1");
        doNothing().when(entityManager).persist(any(Event.class));
        doThrow(new DataIntegrityViolationException("duplicate pk")).when(entityManager).flush();
        when(eventRepository.findBySessionIdAndSequence(1L, 10L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.append(1L, request))
                .isInstanceOf(DuplicateEventException.class)
                .matches(e -> ((DuplicateEventException) e).getExistingEventId().equals(99L))
                .matches(e -> ((DuplicateEventException) e).getExistingSequence().equals(10L));
    }

    @Test
    void append_case3_conflict_different_clientId_throws_invalid_sequence() {
        AppendEventRequest request = request(10L, "c1");
        Event existing = event(99L, 10L, "other-id");
        doNothing().when(entityManager).persist(any(Event.class));
        doThrow(new DataIntegrityViolationException("duplicate pk")).when(entityManager).flush();
        when(eventRepository.findBySessionIdAndSequence(1L, 10L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.append(1L, request))
                .isInstanceOf(InvalidSequenceException.class);
    }

    @Test
    void append_case5_duplicate_clientId_different_sequence_returns_duplicate() {
        AppendEventRequest request = request(10L, "c1");
        Event existing = event(99L, 5L, "c1");
        doNothing().when(entityManager).persist(any(Event.class));
        doThrow(new DataIntegrityViolationException("duplicate uk")).when(entityManager).flush();
        when(eventRepository.findBySessionIdAndSequence(1L, 10L)).thenReturn(Optional.empty());
        when(eventRepository.findBySessionIdAndClientEventId(1L, "c1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.append(1L, request))
                .isInstanceOf(DuplicateEventException.class)
                .matches(e -> ((DuplicateEventException) e).getExistingSequence().equals(5L));
    }

    @Test
    void append_case6_unexpected_violation_rethrows_original() {
        AppendEventRequest request = request(10L, "c1");
        DataIntegrityViolationException original = new DataIntegrityViolationException("unknown");
        doNothing().when(entityManager).persist(any(Event.class));
        doThrow(original).when(entityManager).flush();
        when(eventRepository.findBySessionIdAndSequence(1L, 10L)).thenReturn(Optional.empty());
        when(eventRepository.findBySessionIdAndClientEventId(1L, "c1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.append(1L, request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private AppendEventRequest request(Long sequence, String clientEventId) {
        return new AppendEventRequest(
                clientEventId,
                "alice",
                sequence,
                EventType.MESSAGE,
                Map.of("text", "hi"),
                LocalDateTime.now()
        );
    }

    private Event event(Long id, Long sequence, String clientEventId) {
        Event event = Event.builder()
                .sessionId(1L)
                .sequence(sequence)
                .clientEventId(clientEventId)
                .userId("alice")
                .type(EventType.MESSAGE)
                .payload("{}")
                .clientTimestamp(LocalDateTime.now())
                .build();
        setField(event, "id", id);
        return event;
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
