package com.example.chat.projection.repository;

import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.domain.SnapshotId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SnapshotRepository extends JpaRepository<Snapshot, SnapshotId> {

    Optional<Snapshot> findTopBySessionIdOrderByVersionDesc(Long sessionId);

    /**
     * 특정 시점 복원용 최신 스냅샷 조회.
     *
     * <p>비동기 스냅샷 생성 환경에서 createdAt은 시간 왜곡이 발생할 수 있어
     * lastSequence(이벤트 진행 위치)를 기준으로 조회한다.
     */
    Optional<Snapshot> findTopBySessionIdAndLastSequenceLessThanEqualOrderByVersionDesc(
            Long sessionId, Long maxSequence);

    int countBySessionId(Long sessionId);

    /**
     * @Immutable 엔티티는 Spring Data의 derived delete가 Hibernate에서 무시되므로,
     * retention 정리/rebuild 시 영속성 컨텍스트를 우회하는 Native DELETE를 사용한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value = "DELETE FROM snapshots WHERE session_id = :sessionId AND version < :version",
            nativeQuery = true
    )
    int deleteOldSnapshotsNative(@Param("sessionId") Long sessionId,
                                 @Param("version") Integer version);

    @Modifying(clearAutomatically = true)
    @Query(
            value = "DELETE FROM snapshots WHERE session_id = :sessionId",
            nativeQuery = true
    )
    int deleteAllBySessionIdNative(@Param("sessionId") Long sessionId);
}
