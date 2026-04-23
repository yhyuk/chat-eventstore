package com.example.chat.event.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "events",
    uniqueConstraints = @UniqueConstraint(name = "uk_session_client_event", columnNames = {"session_id", "client_event_id"})
)
@IdClass(EventId.class)
@DynamicInsert
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Event {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Id
    @Column(name = "sequence")
    private Long sequence;

    // PK가 아닌 AUTO_INCREMENT 대리 키 — DB가 INSERT 시 생성하며 애플리케이션에서 직접 설정하지 않는다.
    // @GeneratedValue를 의도적으로 생략: 해당 어노테이션은 PK 컬럼 전용이므로 @Generated(INSERT)를 사용한다.
    @Column(name = "id", unique = true, insertable = false, updatable = false)
    @Generated(event = org.hibernate.generator.EventType.INSERT)
    private Long id;

    @Column(name = "client_event_id", nullable = false, length = 64)
    private String clientEventId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    // 같은 패키지의 EventType을 사용하며, @Generated에서 org.hibernate.generator.EventType과의 이름 충돌을 피하기 위해 완전 한정명을 사용한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json", nullable = false)
    private String payload;

    @Column(name = "client_timestamp", nullable = false)
    private LocalDateTime clientTimestamp;

    @Column(name = "server_received_at", updatable = false)
    @Generated(event = org.hibernate.generator.EventType.INSERT)
    private LocalDateTime serverReceivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "projection_status", length = 16, insertable = false)
    @Generated(event = org.hibernate.generator.EventType.INSERT)
    private ProjectionStatus projectionStatus;

    @Column(name = "retry_count", insertable = false)
    @Generated(event = org.hibernate.generator.EventType.INSERT)
    private Integer retryCount;

    @Column(name = "next_retry_at", insertable = false)
    @Generated(event = org.hibernate.generator.EventType.INSERT)
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;
}
