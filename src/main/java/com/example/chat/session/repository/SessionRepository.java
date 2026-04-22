package com.example.chat.session.repository;

import com.example.chat.session.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, Long> {

    // GREATEST guarantees monotonic growth even when events arrive out of order.
    // clearAutomatically = true flushes the persistence context so subsequent reads see fresh values.
    // clearAutomatically flushes the persistence context of Session so a subsequent read sees the new value.
    // flushAutomatically is intentionally off — the Event was already persisted via saveAndFlush() and an
    // auto-flush here would re-fire dirty-check UPDATEs for @DynamicInsert-only columns that are still null.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE sessions SET last_sequence = GREATEST(last_sequence, :seq), "
                    + "version = version + 1 WHERE id = :id",
            nativeQuery = true
    )
    int updateLastSequence(@Param("id") Long id, @Param("seq") Long seq);
}
