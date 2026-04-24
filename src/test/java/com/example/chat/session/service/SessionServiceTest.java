package com.example.chat.session.service;

import com.example.chat.common.exception.SessionEndedException;
import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.dto.AppendResult;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.service.SnapshotService;
import com.example.chat.realtime.dto.EventBroadcastFrame;
import com.example.chat.realtime.pubsub.RedisMessagePublisher;
import com.example.chat.realtime.service.RecentCacheService;
import com.example.chat.realtime.service.ResumeService;
import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.dto.CreateSessionResponse;
import com.example.chat.session.dto.EndSessionResponse;
import com.example.chat.session.dto.JoinSessionResponse;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private ParticipantRepository participantRepository;

    @Mock
    private SnapshotService snapshotService;

    @Mock
    private EventAppendService eventAppendService;

    @Mock
    private RedisMessagePublisher publisher;

    @Mock
    private RecentCacheService recentCacheService;

    @Mock
    private ResumeService resumeService;

    @InjectMocks
    private SessionService sessionService;

    @Test
    void createSession_returns_response_with_active_status() {
        Session persisted = Session.create();
        setField(persisted, "id", 1L);
        setField(persisted, "createdAt", LocalDateTime.now());
        when(sessionRepository.saveAndFlush(any(Session.class))).thenReturn(persisted);

        CreateSessionResponse response = sessionService.createSession();

        assertThat(response.sessionId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(response.createdAt()).isNotNull();
        verify(sessionRepository).saveAndFlush(any(Session.class));
    }

    @Test
    void joinSession_success_appends_join_event_with_next_sequence() {
        Session session = Session.create();
        setField(session, "id", 1L);
        setField(session, "lastSequence", 7L);
        when(sessionRepository.findWithLockById(1L)).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionAndUserId(session, "alice")).thenReturn(Optional.empty());
        when(participantRepository.save(any(Participant.class))).thenAnswer(inv -> inv.getArgument(0));

        Event saved = Event.builder().sessionId(1L).sequence(8L).build();
        when(eventAppendService.append(eq(1L), any(AppendEventRequest.class)))
                .thenReturn(AppendResult.accepted(saved));
        EventBroadcastFrame.EventPayload payload = new EventBroadcastFrame.EventPayload(
                null, 1L, 8L, "cid", "alice", "JOIN", null, null, null);
        when(resumeService.toPayload(saved)).thenReturn(payload);

        JoinSessionResponse response = sessionService.joinSession(1L, "alice");

        assertThat(response.sessionId()).isEqualTo(1L);
        assertThat(response.userId()).isEqualTo("alice");
        assertThat(response.joinedAt()).isNotNull();
        // SessionService.joinSession이 EventAppendService가 반환한 saved.sequence(=8L)를 응답에 직접 실어준다.
        // (엔티티의 lastSequence는 JPQL clearAutomatically 때문에 stale일 수 있어 신뢰하지 않는다.)
        assertThat(response.lastSequence()).isEqualTo(8L);
        verify(participantRepository).save(any(Participant.class));

        ArgumentCaptor<AppendEventRequest> captor = ArgumentCaptor.forClass(AppendEventRequest.class);
        verify(eventAppendService).append(eq(1L), captor.capture());
        AppendEventRequest req = captor.getValue();
        assertThat(req.type()).isEqualTo(EventType.JOIN);
        assertThat(req.userId()).isEqualTo("alice");
        assertThat(req.sequence()).isEqualTo(8L); // lastSequence + 1
        verify(publisher).publish(eq(1L), any(EventBroadcastFrame.class));
        verify(recentCacheService).append(eq(1L), eq(8L), eq(payload));
    }

    @Test
    void joinSession_idempotent_skips_event_append() {
        Session session = Session.create();
        setField(session, "id", 1L);
        LocalDateTime originalJoinedAt = LocalDateTime.now().minusHours(1);
        Participant existing = Participant.builder()
                .session(session)
                .userId("alice")
                .joinedAt(originalJoinedAt)
                .build();
        when(sessionRepository.findWithLockById(1L)).thenReturn(Optional.of(session));
        when(participantRepository.findBySessionAndUserId(session, "alice")).thenReturn(Optional.of(existing));

        JoinSessionResponse response = sessionService.joinSession(1L, "alice");

        assertThat(response.joinedAt()).isEqualTo(originalJoinedAt);
        verify(participantRepository, never()).save(any(Participant.class));
        verify(eventAppendService, never()).append(anyLong(), any(AppendEventRequest.class));
        verify(publisher, never()).publish(anyLong(), any(EventBroadcastFrame.class));
    }

    @Test
    void joinSession_session_not_found_throws() {
        when(sessionRepository.findWithLockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.joinSession(99L, "alice"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void joinSession_session_ended_throws() {
        Session session = Session.create();
        setField(session, "id", 1L);
        session.end();
        when(sessionRepository.findWithLockById(1L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> sessionService.joinSession(1L, "alice"))
                .isInstanceOf(SessionEndedException.class);
    }

    @Test
    void endSession_success() {
        Session session = Session.create();
        setField(session, "id", 1L);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(sessionRepository.saveAndFlush(session)).thenReturn(session);

        Instant before = Instant.now().minusMillis(1);
        EndSessionResponse response = sessionService.endSession(1L);
        Instant after = Instant.now().plusMillis(1);

        assertThat(response.status()).isEqualTo(SessionStatus.ENDED);
        assertThat(response.endedAt()).isNotNull();
        Instant endedInstant = response.endedAt().atZone(java.time.ZoneId.systemDefault()).toInstant();
        assertThat(endedInstant).isBetween(before, after);
    }

    @Test
    void endSession_idempotent_returns_existing() {
        Session session = Session.create();
        setField(session, "id", 1L);
        session.end();
        LocalDateTime originalEndedAt = session.getEndedAt();
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        EndSessionResponse response = sessionService.endSession(1L);

        assertThat(response.endedAt()).isEqualTo(originalEndedAt);
        verify(sessionRepository, never()).saveAndFlush(any(Session.class));
    }

    @Test
    void endSession_session_not_found_throws() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.endSession(99L))
                .isInstanceOf(SessionNotFoundException.class);
    }

    // Reflection helper: Session/Participant only expose package-private setters via Lombok @Builder;
    // tests need to pre-populate DB-generated fields (id, createdAt) without routing through repositories.
    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
