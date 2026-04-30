package com.example.chat.event.service;

import com.example.chat.common.exception.SessionNotFoundException;
import com.example.chat.event.domain.Event;
import com.example.chat.event.dto.MessageHistoryResponse;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.realtime.dto.EventBroadcastFrame.EventPayload;
import com.example.chat.realtime.service.ResumeService;
import com.example.chat.session.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 채팅 히스토리 페이지네이션 서비스.
 *
 * <p>책임 구분:
 * <ul>
 *   <li>{@link com.example.chat.restore.service.EventReplayService} — 특정 시점 상태 복원(감사/디버깅).</li>
 *   <li>{@link com.example.chat.realtime.service.ResumeService} — WebSocket 재접속 시 누락 이벤트 보충.</li>
 *   <li>이 서비스 — 사용자 UI용 채팅 히스토리 페이지 조회.</li>
 * </ul>
 *
 * <p>커서 기반(cursor) 페이지네이션을 사용한다. PK가 {@code (session_id, sequence)}이므로
 * {@code WHERE session_id = ? AND sequence < ? ORDER BY sequence DESC LIMIT ?}는
 * 인덱스 점프로 즉시 처리되며 OFFSET 페이지네이션처럼 앞 페이지를 다시 읽지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MessageHistoryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final SessionRepository sessionRepository;
    private final EventRepository eventRepository;
    private final ResumeService resumeService;

    @Transactional(readOnly = true)
    public MessageHistoryResponse getHistory(Long sessionId, Long before, int limit) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new SessionNotFoundException(sessionId);
        }
        int effectiveLimit = clampLimit(limit);

        // hasMore 판정을 위해 limit + 1개를 시도 조회한다.
        // 결과가 limit + 1개면 다음 페이지가 존재.
        Pageable pageable = PageRequest.of(0, effectiveLimit + 1);

        List<Event> rows = (before == null)
                ? eventRepository.findBySessionIdOrderBySequenceDesc(sessionId, pageable)
                : eventRepository.findBySessionIdAndSequenceLessThanOrderBySequenceDesc(
                sessionId, before, pageable);

        if (rows.isEmpty()) {
            return MessageHistoryResponse.empty();
        }

        boolean hasMore = rows.size() > effectiveLimit;
        List<Event> page = hasMore ? rows.subList(0, effectiveLimit) : rows;
        List<EventPayload> messages = page.stream().map(resumeService::toPayload).toList();
        Long nextBefore = hasMore ? messages.get(messages.size() - 1).sequence() : null;

        return new MessageHistoryResponse(messages, nextBefore, hasMore);
    }

    private int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
