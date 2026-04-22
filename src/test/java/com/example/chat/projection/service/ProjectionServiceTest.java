package com.example.chat.projection.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.repository.SessionProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ProjectionServiceTest {

    @Mock
    private SessionProjectionRepository repository;

    private ProjectionService service;

    @BeforeEach
    void setUp() {
        service = new ProjectionService(repository);
    }

    @Test
    void message_increments_message_count() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        Event event = event(100L, EventType.MESSAGE, 1L);

        service.apply(event);

        ArgumentCaptor<Long> msgCount = ArgumentCaptor.forClass(Long.class);
        verify(repository).upsertProjection(eq(1L), eq(0), msgCount.capture(), any(), eq(100L));
        assertThat(msgCount.getValue()).isEqualTo(1L);
    }

    @Test
    void join_increments_participant_count() {
        SessionProjection existing = SessionProjection.builder()
                .sessionId(1L).participantCount(2).messageCount(5L)
                .lastAppliedEventId(50L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        Event event = event(100L, EventType.JOIN, 1L);

        service.apply(event);

        verify(repository).upsertProjection(eq(1L), eq(3), eq(5L), any(), eq(100L));
    }

    @Test
    void edit_leaves_counters_unchanged() {
        SessionProjection existing = SessionProjection.builder()
                .sessionId(1L).participantCount(2).messageCount(5L)
                .lastAppliedEventId(50L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        Event event = event(100L, EventType.EDIT, 1L);

        service.apply(event);

        verify(repository).upsertProjection(eq(1L), eq(2), eq(5L), any(), eq(100L));
    }

    @Test
    void delete_leaves_counters_unchanged() {
        SessionProjection existing = SessionProjection.builder()
                .sessionId(1L).participantCount(2).messageCount(5L)
                .lastAppliedEventId(50L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        Event event = event(100L, EventType.DELETE, 1L);

        service.apply(event);

        verify(repository).upsertProjection(eq(1L), eq(2), eq(5L), any(), eq(100L));
    }

    private Event event(long id, EventType type, Long sessionId) {
        Event e = Event.builder()
                .sessionId(sessionId)
                .sequence(id)
                .clientEventId("c" + id)
                .userId("alice")
                .type(type)
                .payload("{}")
                .clientTimestamp(LocalDateTime.now())
                .build();
        setField(e, "id", id);
        setField(e, "serverReceivedAt", LocalDateTime.now());
        return e;
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
