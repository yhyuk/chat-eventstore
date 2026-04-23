package com.example.chat.projection.service;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventType;
import com.example.chat.projection.domain.SessionProjection;
import com.example.chat.projection.repository.SessionProjectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

// 단일 이벤트를 읽기 모델 카운터에 적용한다. 퍼시스턴스 컨텍스트 분기 없이
// 아래 단일 upsert가 최초 INSERT와 멱등 UPDATE 경로를 모두 처리한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectionService {

    private final SessionProjectionRepository repository;

    public void apply(Event event) {
        Optional<SessionProjection> current = repository.findById(event.getSessionId());
        int participantCount = current.map(SessionProjection::getParticipantCount).orElse(0);
        long messageCount = current.map(SessionProjection::getMessageCount).orElse(0L);
        LocalDateTime lastMessageAt = current.map(SessionProjection::getLastMessageAt).orElse(null);

        EventType type = event.getType();
        switch (type) {
            case MESSAGE -> {
                messageCount += 1;
                lastMessageAt = event.getServerReceivedAt();
            }
            case JOIN -> participantCount += 1;
            case LEAVE -> participantCount = Math.max(0, participantCount - 1);
            case DISCONNECT, RECONNECT, EDIT, DELETE -> {
                // Tombstone(EDIT/DELETE)과 접속 상태 전용 이벤트는 집계 카운터를 변경하지 않는다.
            }
        }

        // last_applied_event_id가 중복 적용을 방지한다: 유입 이벤트 id가 이미 저장된 값보다
        // 클 때만 카운터를 덮어쓴다.
        repository.upsertProjection(
                event.getSessionId(),
                participantCount,
                messageCount,
                lastMessageAt,
                event.getId()
        );
    }
}
