package com.example.chat.projection.repository;

import com.example.chat.projection.domain.SessionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface SessionProjectionRepository extends JpaRepository<SessionProjection, Long> {

    // Native UPSERT with MySQL 8.0.20+ row alias syntax (avoids VALUES() deprecation).
    // Idempotency: only overwrite counter fields when the incoming event is newer than
    // what is already persisted (last_applied_event_id guards against double-apply).
    @Modifying(clearAutomatically = true)
    @Query(
            value = "INSERT INTO session_projection "
                    + "(session_id, participant_count, message_count, last_message_at, last_applied_event_id) "
                    + "VALUES (:sessionId, :participantCount, :messageCount, :lastMessageAt, :lastAppliedEventId) "
                    + "AS new "
                    + "ON DUPLICATE KEY UPDATE "
                    + "  participant_count = CASE WHEN session_projection.last_applied_event_id < new.last_applied_event_id "
                    + "                           THEN new.participant_count ELSE session_projection.participant_count END, "
                    + "  message_count = CASE WHEN session_projection.last_applied_event_id < new.last_applied_event_id "
                    + "                       THEN new.message_count ELSE session_projection.message_count END, "
                    + "  last_message_at = CASE WHEN session_projection.last_applied_event_id < new.last_applied_event_id "
                    + "                         THEN new.last_message_at ELSE session_projection.last_message_at END, "
                    + "  last_applied_event_id = CASE WHEN session_projection.last_applied_event_id < new.last_applied_event_id "
                    + "                               THEN new.last_applied_event_id ELSE session_projection.last_applied_event_id END",
            nativeQuery = true
    )
    int upsertProjection(@Param("sessionId") Long sessionId,
                         @Param("participantCount") Integer participantCount,
                         @Param("messageCount") Long messageCount,
                         @Param("lastMessageAt") LocalDateTime lastMessageAt,
                         @Param("lastAppliedEventId") Long lastAppliedEventId);

    // D5 rebuild: wipe projection row so the subsequent upsert inserts a fresh INSERT path
    // without tripping the last_applied_event_id monotonicity guard.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "DELETE FROM session_projection WHERE session_id = :sessionId",
            nativeQuery = true
    )
    int deleteByIdNative(@Param("sessionId") Long sessionId);
}
