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

    // D5 restore: pick the newest snapshot that covers events up to a given maxSequence.
    // Using lastSequence (not createdAt) avoids the time-skew caused by async snapshot creation.
    Optional<Snapshot> findTopBySessionIdAndLastSequenceLessThanEqualOrderByVersionDesc(
            Long sessionId, Long maxSequence);

    int countBySessionId(Long sessionId);

    // @Immutable entity: Spring Data derived delete is silently ignored by Hibernate,
    // so retention cleanup uses a Native DELETE to bypass the persistence context entirely.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "DELETE FROM snapshots WHERE session_id = :sessionId AND version < :version",
            nativeQuery = true
    )
    int deleteOldSnapshotsNative(@Param("sessionId") Long sessionId,
                                 @Param("version") Integer version);

    // D5 rebuild: wipe all snapshots for a session prior to full replay.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "DELETE FROM snapshots WHERE session_id = :sessionId",
            nativeQuery = true
    )
    int deleteAllBySessionIdNative(@Param("sessionId") Long sessionId);
}
