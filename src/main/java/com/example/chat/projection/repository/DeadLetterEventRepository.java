package com.example.chat.projection.repository;

import com.example.chat.projection.domain.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    List<DeadLetterEvent> findAllByOrderByMovedAtDesc();

    // @Immutable 엔티티는 Hibernate의 derived deleteById가 no-op이므로,
    // 관리자 삭제 시 surrogate id 컬럼을 기준으로 Native DELETE를 직접 실행한다.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "DELETE FROM dead_letter_events WHERE id = :id",
            nativeQuery = true
    )
    int deleteByIdNative(@Param("id") Long id);
}
