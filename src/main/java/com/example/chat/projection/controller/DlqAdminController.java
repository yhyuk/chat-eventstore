package com.example.chat.projection.controller;

import com.example.chat.common.exception.ErrorCode;
import com.example.chat.common.exception.ErrorResponse;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.ProjectionStatus;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.domain.DeadLetterEvent;
import com.example.chat.projection.dto.DlqEventResponse;
import com.example.chat.projection.repository.DeadLetterEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 인증은 과제 Non-goal. 프로덕션 환경에서는 네트워크 격리나 시큐리티 필터로 보호해야 한다 (README 참고).
@Slf4j
@RestController
@RequestMapping("/admin/dlq")
@RequiredArgsConstructor
public class DlqAdminController {

    private final DeadLetterEventRepository dlqRepository;
    private final EventRepository eventRepository;

    @GetMapping
    public List<DlqEventResponse> list() {
        return dlqRepository.findAllByOrderByMovedAtDesc().stream()
                .map(DlqEventResponse::from)
                .toList();
    }

    // 단일 트랜잭션으로 PENDING 초기화와 DLQ 행 삭제를 묶는다 — 둘 중 하나만 커밋되면 중복 재처리 루프가 발생할 수 있다.
    @PostMapping("/{id}/retry")
    @Transactional
    public ResponseEntity<?> retry(@PathVariable Long id) {
        Optional<DeadLetterEvent> dlqOpt = dlqRepository.findById(id);
        if (dlqOpt.isEmpty()) {
            return notFound("DLQ entry not found: id=" + id);
        }
        DeadLetterEvent dlq = dlqOpt.get();

        Optional<Event> originalOpt = eventRepository.findBySessionIdAndSequence(
                dlq.getSessionId(), dlq.getSequence());
        if (originalOpt.isEmpty()) {
            return notFound("Original event not found: sessionId=" + dlq.getSessionId()
                    + ", sequence=" + dlq.getSequence());
        }

        eventRepository.updateProjectionStatus(
                dlq.getSessionId(),
                dlq.getSequence(),
                ProjectionStatus.PENDING,
                0,
                LocalDateTime.now(),
                null
        );
        dlqRepository.deleteByIdNative(id);
        log.info("DLQ retry accepted: id={}, sessionId={}, sequence={}",
                id, dlq.getSessionId(), dlq.getSequence());
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> discard(@PathVariable Long id) {
        dlqRepository.deleteByIdNative(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ErrorResponse> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(
                        ErrorCode.SESSION_NOT_FOUND.name(),
                        message,
                        LocalDateTime.now()));
    }
}
