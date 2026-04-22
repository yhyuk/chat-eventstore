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
}
