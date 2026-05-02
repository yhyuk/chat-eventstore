package com.example.chat.event.repository;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventId;
import com.example.chat.event.domain.ProjectionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, EventId>, EventQueryRepository {

    Optional<Event> findBySessionIdAndSequence(Long sessionId, Long sequence);

    Optional<Event> findBySessionIdAndClientEventId(Long sessionId, String clientEventId);

    List<Event> findBySessionIdAndSequenceGreaterThanOrderBySequenceAsc(Long sessionId, Long sequence);

    List<Event> findBySessionIdAndSequenceGreaterThanAndServerReceivedAtLessThanEqualOrderBySequenceAsc(
            Long sessionId, Long sequenceExclusive, LocalDateTime at);

    /**
     * 채팅 히스토리 페이지네이션 — 최신부터 N개.
     *
     * <p>PK가 (session_id, sequence)이므로 인덱스만으로 ORDER BY DESC + LIMIT가 빠르게 동작.
     * 페이지네이션 첫 페이지(before 미지정)에서 사용한다.
     */
    List<Event> findBySessionIdOrderBySequenceDesc(Long sessionId, Pageable pageable);

    /**
     * 채팅 히스토리 페이지네이션 — before sequence 미만의 N개.
     *
     * <p>커서 기반(cursor-based) 페이지네이션. offset 대신 sequence를 커서로 사용해
     * 메시지가 많아져도 인덱스 점프로 즉시 위치를 찾는다.
     */
    List<Event> findBySessionIdAndSequenceLessThanOrderBySequenceDesc(
            Long sessionId, Long sequenceExclusive, Pageable pageable);

    @Query("SELECT MAX(e.sequence) FROM Event e "
            + "WHERE e.sessionId = :sessionId AND e.serverReceivedAt <= :at")
    Optional<Long> findMaxSequenceBySessionIdAndServerReceivedAtLessThanEqual(
            @Param("sessionId") Long sessionId, @Param("at") LocalDateTime at);

    /**
     * 아웃박스 폴링용 배치 조회.
     *
     * <p>JPQL이 지원하지 않는 {@code FOR UPDATE SKIP LOCKED}를 사용하기 위해 Native SQL로 작성.
     * 다중 워커가 동일 행을 잡지 않도록 락 충돌 시 즉시 다음 행으로 건너뛴다.
     * Event 엔티티 대신 인터페이스 프로젝션을 반환하여 영속성 컨텍스트 오염을 방지한다.
     * FAILED 이벤트는 DLQ로 이관되어 있으므로 재시도 API를 통해서만 재진입한다.
     */
    @Query(
            value = "SELECT id AS id, session_id AS sessionId, sequence AS sequence "
                    + "FROM events "
                    + "WHERE projection_status = 'PENDING' "
                    + "  AND next_retry_at <= CURRENT_TIMESTAMP(3) "
                    + "ORDER BY id ASC "
                    + "LIMIT :limit "
                    + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true
    )
    List<EventIdProjection> fetchPendingEventIds(@Param("limit") int limit);

    long countByProjectionStatus(ProjectionStatus projectionStatus);

    @Query("SELECT MIN(e.serverReceivedAt) FROM Event e WHERE e.projectionStatus = 'PENDING'")
    Optional<LocalDateTime> findOldestPendingServerReceivedAt();

    /**
     * 아웃박스 워커가 갱신하는 projection 상태 필드 전용 UPDATE.
     *
     * <p>payload, user_id 등 비즈니스 컬럼은 절대 변경하지 않는다(이벤트 소싱 불변성).
     * 복합키 (session_id, sequence)로 단일 행만 갱신.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Event e "
            + "SET e.projectionStatus = :status, "
            + "    e.retryCount = :retryCount, "
            + "    e.nextRetryAt = :nextRetryAt, "
            + "    e.lastError = :lastError "
            + "WHERE e.sessionId = :sessionId AND e.sequence = :sequence")
    int updateProjectionStatus(@Param("sessionId") Long sessionId,
                               @Param("sequence") Long sequence,
                               @Param("status") ProjectionStatus status,
                               @Param("retryCount") Integer retryCount,
                               @Param("nextRetryAt") LocalDateTime nextRetryAt,
                               @Param("lastError") String lastError);
}
