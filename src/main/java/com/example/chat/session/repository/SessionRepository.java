package com.example.chat.session.repository;

import com.example.chat.session.domain.Session;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long>, SessionQueryRepository {

    // sessions 행에 PESSIMISTIC_WRITE 락을 걸어 rebuild와 append 경로의 updateLastSequence()가
    // 동일 행을 동시에 수정하지 못하도록 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Session s WHERE s.id = :id")
    Optional<Session> findWithLockById(@Param("id") Long id);

    // GREATEST로 단조 증가 보장: 이벤트가 순서 없이 도착해도 last_sequence가 감소하지 않는다.
    // clearAutomatically=true: 이후 읽기에서 갱신된 값이 보이도록 영속성 컨텍스트를 비운다.
    // flushAutomatically는 의도적으로 비활성: Event는 이미 persist됐으며 여기서 auto-flush하면
    // @DynamicInsert 전용 null 컬럼에 대해 불필요한 더티 체킹 UPDATE가 재발생한다.
    @Modifying(clearAutomatically = true)
    @Query(
            value = "UPDATE sessions SET last_sequence = GREATEST(last_sequence, :seq), "
                    + "version = version + 1 WHERE id = :id",
            nativeQuery = true
    )
    int updateLastSequence(@Param("id") Long id, @Param("seq") Long seq);
}
