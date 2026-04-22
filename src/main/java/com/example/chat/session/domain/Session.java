package com.example.chat.session.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "sessions")
@DynamicInsert
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    @Column(name = "created_at", updatable = false)
    @Generated(event = EventType.INSERT)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    @Version
    private Long version;

    public static Session create() {
        Session session = new Session();
        session.status = SessionStatus.ACTIVE;
        session.lastSequence = 0L;
        return session;
    }

    public void end() {
        if (isEnded()) {
            return;
        }
        this.status = SessionStatus.ENDED;
        // Truncate to MySQL DATETIME(3) precision so in-memory value matches what the DB will return on re-read.
        this.endedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public boolean isEnded() {
        return this.status == SessionStatus.ENDED;
    }
}
