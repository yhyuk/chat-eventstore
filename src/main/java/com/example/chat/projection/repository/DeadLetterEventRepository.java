package com.example.chat.projection.repository;

import com.example.chat.projection.domain.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    List<DeadLetterEvent> findAllByOrderByMovedAtDesc();

    // @Immutable entity: derived deleteById is a no-op in Hibernate, so admin cleanup
    // uses a Native DELETE keyed by the surrogate id column.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "DELETE FROM dead_letter_events WHERE id = :id",
            nativeQuery = true
    )
    int deleteByIdNative(@Param("id") Long id);
}
