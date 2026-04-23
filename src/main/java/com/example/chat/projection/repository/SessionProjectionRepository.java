package com.example.chat.projection.repository;

import com.example.chat.projection.domain.SessionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface SessionProjectionRepository extends JpaRepository<SessionProjection, Long> {

    /**
     * MySQL 8.0.20+ row alias 문법을 사용한 Native UPSERT.
     *
     * <p>JPQL은 UPSERT 구문을 지원하지 않으므로 Native가 강제된다.
     * 또한 VALUES() 함수가 deprecated되어 {@code AS new} row alias로 대체했다.
     * 멱등성 보장: 유입 이벤트의 last_applied_event_id가 저장된 값보다 큰 경우에만 카운터를 덮어쓴다.
     */
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

    /**
     * rebuild 시 projection 행을 삭제하여 이후 upsert가 last_applied_event_id 단조 증가 가드에 걸리지 않고
     * 신규 INSERT 경로로 진입하도록 한다.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    int deleteBySessionId(Long sessionId);
}
