package com.example.chat.projection.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "dead_letter_events")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_event_id", nullable = false)
    private Long originalEventId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    // DLQ 재시도 시 (session_id, sequence)로 원본 이벤트를 찾기 위해 추가된 컬럼 (V6).
    @Column(name = "sequence", nullable = false)
    private Long sequence;

    // EventType enum이 변경되어도 DLQ 레코드가 살아남도록 raw String으로 저장.
    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json", nullable = false)
    private String payload;

    @Column(name = "error_message", nullable = false, length = 1024)
    private String errorMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    // @Immutable + @Generated 조합이 Hibernate 6.5에서 lock-mode 오류를 발생시키므로,
    // DB DEFAULT 읽기 대신 @PrePersist에서 직접 할당한다.
    @Column(name = "moved_at", updatable = false, nullable = false)
    private LocalDateTime movedAt;

    @PrePersist
    void onCreate() {
        if (movedAt == null) {
            movedAt = LocalDateTime.now();
        }
    }
}
