package com.example.chat.restore.service;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.EventType;
import com.example.chat.event.dto.AppendEventRequest;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.event.service.EventAppendService;
import com.example.chat.projection.dto.SessionState.MessageState;
import com.example.chat.projection.repository.SessionProjectionRepository;
import com.example.chat.projection.repository.SnapshotRepository;
import com.example.chat.projection.service.SnapshotService;
import com.example.chat.restore.dto.TimelineResponse;
import com.example.chat.session.domain.Session;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// D5 determinism suite.
// The invariant under test: identical `at` must produce identical TimelineResponse,
// regardless of how many snapshots exist or when the call is made.
class EventReplayServiceIntegrationTest extends AbstractFullIntegrationTest {

    @Autowired private EventReplayService eventReplayService;
    @Autowired private EventAppendService eventAppendService;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private ParticipantRepository participantRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SnapshotRepository snapshotRepository;
    @Autowired private SessionProjectionRepository projectionRepository;
    @Autowired private SnapshotService snapshotService;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        snapshotRepository.deleteAll();
        projectionRepository.deleteAll();
        eventRepository.deleteAllInBatch();
        participantRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        sessionId = sessionRepository.saveAndFlush(Session.create()).getId();
    }

    @Test
    void T2_1_identical_at_yields_identical_response() {
        appendMessage(1L, "c1", "alice", "hello");
        appendMessage(2L, "c2", "alice", "world");
        LocalDateTime at = LocalDateTime.now().plusSeconds(1);

        TimelineResponse r1 = eventReplayService.restoreAt(sessionId, at);
        TimelineResponse r2 = eventReplayService.restoreAt(sessionId, at);

        // Records provide structural equality. This is the core determinism assertion.
        assertThat(r1).isEqualTo(r2);
        assertThat(r1.replayedEventCount()).isEqualTo(2);
    }

    @Test
    void T2_2_at_before_edit_returns_original_text() throws Exception {
        appendMessage(1L, "c1", "alice", "original");
        // Use the server-generated server_received_at of the original message as the boundary.
        // This removes any flakiness from LocalDateTime.now() vs DB millisecond rounding.
        LocalDateTime beforeEdit = eventRepository
                .findBySessionIdAndClientEventId(sessionId, "c1").orElseThrow().getServerReceivedAt();
        Thread.sleep(20);
        // targetEventId refers to the MESSAGE sequence (state.messages map key in StateEventApplier).
        appendEdit(2L, "c2", "alice", 1L, "edited");

        TimelineResponse r = eventReplayService.restoreAt(sessionId, beforeEdit);

        assertThat(r.messages()).hasSize(1);
        MessageState msg = r.messages().iterator().next();
        assertThat(msg.text()).isEqualTo("original");
        assertThat(msg.status()).isEqualTo(MessageState.SENT);
    }

    @Test
    void T2_3_at_after_edit_returns_edited_text() throws Exception {
        appendMessage(1L, "c1", "alice", "original");
        Thread.sleep(20);
        appendEdit(2L, "c2", "alice", 1L, "edited");
        LocalDateTime afterEdit = LocalDateTime.now().plusSeconds(1);

        TimelineResponse r = eventReplayService.restoreAt(sessionId, afterEdit);

        assertThat(r.messages()).hasSize(1);
        MessageState msg = r.messages().iterator().next();
        assertThat(msg.text()).isEqualTo("edited");
        assertThat(msg.status()).isEqualTo(MessageState.EDITED);
    }

    @Test
    void T2_4_no_snapshot_full_replay() {
        for (int i = 1; i <= 5; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }
        LocalDateTime at = LocalDateTime.now().plusSeconds(1);

        TimelineResponse r = eventReplayService.restoreAt(sessionId, at);

        assertThat(r.restoredFromSnapshot()).isFalse();
        assertThat(r.snapshotVersion()).isNull();
        assertThat(r.replayedEventCount()).isEqualTo(5);
        assertThat(r.messages()).hasSize(5);
    }

    @Test
    void T2_5_snapshot_reduces_replay_cost() {
        for (int i = 1; i <= 5; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }
        snapshotService.createSnapshot(sessionId);
        // Append events beyond the snapshot; only those should replay.
        for (int i = 6; i <= 7; i++) {
            appendMessage(i, "c" + i, "alice", "msg-" + i);
        }
        LocalDateTime at = LocalDateTime.now().plusSeconds(1);

        TimelineResponse r = eventReplayService.restoreAt(sessionId, at);

        assertThat(r.restoredFromSnapshot()).isTrue();
        assertThat(r.snapshotVersion()).isEqualTo(1);
        assertThat(r.replayedEventCount()).isEqualTo(2);   // only msgs 6, 7
        assertThat(r.messages()).hasSize(7);
    }

    @Test
    void T2_6_at_before_any_event_returns_empty_state() {
        appendMessage(1L, "c1", "alice", "hello");
        LocalDateTime before = LocalDateTime.now().minusYears(1);

        TimelineResponse r = eventReplayService.restoreAt(sessionId, before);

        assertThat(r.replayedEventCount()).isZero();
        assertThat(r.restoredFromSnapshot()).isFalse();
        assertThat(r.messages()).isEmpty();
        assertThat(r.participants()).isEmpty();
    }

    @Test
    void T2_7_unknown_session_throws_not_found() {
        assertThatThrownBy(() -> eventReplayService.restoreAt(9_999_999L, LocalDateTime.now()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void T2_8_delete_event_changes_status_to_deleted() throws Exception {
        appendMessage(1L, "c1", "alice", "to-be-deleted");
        Thread.sleep(20);
        appendDelete(2L, "c2", "alice", 1L);
        LocalDateTime afterDelete = LocalDateTime.now().plusSeconds(1);

        TimelineResponse r = eventReplayService.restoreAt(sessionId, afterDelete);

        assertThat(r.messages()).hasSize(1);
        MessageState msg = r.messages().iterator().next();
        assertThat(msg.status()).isEqualTo(MessageState.DELETED);
    }

    private void appendMessage(long sequence, String clientEventId, String user, String text) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, user, sequence, EventType.MESSAGE,
                Map.of("text", text), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }

    private void appendEdit(long sequence, String clientEventId, String user,
                             Long targetEventId, String newText) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, user, sequence, EventType.EDIT,
                Map.of("targetEventId", targetEventId, "newText", newText), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }

    private void appendDelete(long sequence, String clientEventId, String user, Long targetEventId) {
        AppendEventRequest req = new AppendEventRequest(
                clientEventId, user, sequence, EventType.DELETE,
                Map.of("targetEventId", targetEventId, "reason", "test"), LocalDateTime.now());
        eventAppendService.append(sessionId, req);
    }
}
