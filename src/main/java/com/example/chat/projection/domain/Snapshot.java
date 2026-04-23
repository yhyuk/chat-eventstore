package com.example.chat.projection.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "snapshots")
@IdClass(SnapshotId.class)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Snapshot {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Id
    @Column(name = "version")
    private Integer version;

    @Column(name = "last_event_id", nullable = false)
    private Long lastEventId;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_json", columnDefinition = "json", nullable = false)
    private String stateJson;

    // @Immutable + @Generated 조합이 Hibernate 6.5에서 lock-mode 오류를 발생시키므로,
    // DB DEFAULT 읽기 대신 @PrePersist에서 직접 할당한다.
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
