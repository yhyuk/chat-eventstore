package com.example.chat.projection.repository;

import com.example.chat.projection.domain.Snapshot;
import com.example.chat.projection.domain.SnapshotId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotRepository extends JpaRepository<Snapshot, SnapshotId> {
}
