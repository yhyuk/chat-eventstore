package com.example.chat.projection.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.Generated;

import java.time.LocalDateTime;

@Entity
@Table(name = "session_projection")
@DynamicInsert
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SessionProjection {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "participant_count", nullable = false)
    private Integer participantCount;

    @Column(name = "message_count", nullable = false)
    private Long messageCount;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "last_applied_event_id", nullable = false)
    private Long lastAppliedEventId;

    @Column(name = "updated_at")
    @Generated(event = {org.hibernate.generator.EventType.INSERT, org.hibernate.generator.EventType.UPDATE})
    private LocalDateTime updatedAt;

    public void updateParticipantCount(int count) {
        this.participantCount = count;
    }
}
