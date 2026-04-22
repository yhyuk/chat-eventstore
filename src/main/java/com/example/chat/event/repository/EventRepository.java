package com.example.chat.event.repository;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, EventId> {

    Optional<Event> findBySessionIdAndSequence(Long sessionId, Long sequence);

    Optional<Event> findBySessionIdAndClientEventId(Long sessionId, String clientEventId);

    List<Event> findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(Long sessionId, Long sequence);

    // Batch read for outbox polling. Returns only IDs (interface projection) so the persistence
    // context does not get polluted with entities that carry null @DynamicInsert columns.
    // PENDING only: FAILED events are already in DLQ and only re-enter via the retry API.
    @Query(
            value = "SELECT session_id AS sessionId, sequence AS sequence "
                    + "FROM events "
                    + "WHERE projection_status = 'PENDING' "
                    + "  AND next_retry_at <= CURRENT_TIMESTAMP(3) "
                    + "ORDER BY id ASC "
                    + "LIMIT :limit "
                    + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true
    )
    List<EventIdProjection> fetchPendingEventIds(@Param("limit") int limit);

    // Targeted state-field UPDATE; business columns (payload, user_id, etc.) are never touched.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE events "
                    + "SET projection_status = :status, "
                    + "    retry_count = :retryCount, "
                    + "    next_retry_at = :nextRetryAt, "
                    + "    last_error = :lastError "
                    + "WHERE session_id = :sessionId AND sequence = :sequence",
            nativeQuery = true
    )
    int updateProjectionStatus(@Param("sessionId") Long sessionId,
                               @Param("sequence") Long sequence,
                               @Param("status") String status,
                               @Param("retryCount") Integer retryCount,
                               @Param("nextRetryAt") LocalDateTime nextRetryAt,
                               @Param("lastError") String lastError);
}
