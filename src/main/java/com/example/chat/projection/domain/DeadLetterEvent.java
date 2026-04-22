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

    // Stored as raw String so DLQ records survive future EventType enum changes
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

    // Application-level timestamp: @Immutable + @Generated causes Hibernate 6.5 lock-mode error,
    // so we assign movedAt in @PrePersist instead of relying on DB DEFAULT read-back.
    @Column(name = "moved_at", updatable = false, nullable = false)
    private LocalDateTime movedAt;

    @PrePersist
    void onCreate() {
        if (movedAt == null) {
            movedAt = LocalDateTime.now();
        }
    }
}
