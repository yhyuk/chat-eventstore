package com.example.chat.projection.worker;

import com.example.chat.common.metrics.ChatMetrics;
import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.ProjectionStatus;
import com.example.chat.event.repository.EventIdProjection;
import com.example.chat.event.repository.EventRepository;
import com.example.chat.projection.domain.DeadLetterEvent;
import com.example.chat.projection.repository.DeadLetterEventRepository;
import com.example.chat.projection.service.ProjectionService;
import com.example.chat.projection.service.SnapshotService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class OutboxPoller {

    private final EventRepository eventRepository;
    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ProjectionService projectionService;
    private final SnapshotService snapshotService;
    private final TransactionTemplate batchReadTemplate;
    private final TransactionTemplate eventProcessTemplate;
    private final ChatMetrics chatMetrics;
    private final int batchSize;
    private final int maxRetry;
    private final boolean schedulingEnabled;

    public OutboxPoller(EventRepository eventRepository,
                        DeadLetterEventRepository deadLetterEventRepository,
                        ProjectionService projectionService,
                        SnapshotService snapshotService,
                        PlatformTransactionManager transactionManager,
                        ChatMetrics chatMetrics,
                        @Value("${app.outbox.batch-size:100}") int batchSize,
                        @Value("${app.outbox.max-retry:5}") int maxRetry,
                        @Value("${app.outbox.enabled:true}") boolean schedulingEnabled) {
        this.eventRepository = eventRepository;
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.projectionService = projectionService;
        this.snapshotService = snapshotService;
        this.batchReadTemplate = new TransactionTemplate(transactionManager);
        this.eventProcessTemplate = new TransactionTemplate(transactionManager);
        this.chatMetrics = chatMetrics;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
        this.schedulingEnabled = schedulingEnabled;
    }

    @PostConstruct
    public void registerGauges() {
        chatMetrics.registerPendingGauge(() -> eventRepository.countByProjectionStatus(ProjectionStatus.PENDING));
        chatMetrics.registerLagGauge(() -> {
            LocalDateTime oldest = eventRepository.findOldestPendingServerReceivedAt().orElse(null);
            if (oldest == null) {
                return 0.0;
            }
            long nowEpoch = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
            long oldestEpoch = oldest.toEpochSecond(ZoneOffset.UTC);
            return Math.max(0.0, (double) (nowEpoch - oldestEpoch));
        });
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void scheduled() {
        if (!schedulingEnabled) {
            return;
        }
        drain();
    }

    public void drain() {
        MDC.put("batchId", UUID.randomUUID().toString());
        try {
            // Step 1: batch ID fetch in its own transaction so the SKIP LOCKED row locks are
            // released immediately. Re-fetch + per-event idempotent processing is safe because
            // last_applied_event_id in SessionProjection guards against double application.
            List<EventIdProjection> ids = batchReadTemplate.execute(status ->
                    eventRepository.fetchPendingEventIds(batchSize));

            if (ids == null || ids.isEmpty()) {
                return;
            }

            for (EventIdProjection id : ids) {
                processOne(id.getSessionId(), id.getSequence());
            }
        } catch (Exception ex) {
            // @Scheduled swallows exceptions silently; catch here so DB outages are visible
            // in logs but do not kill the scheduler thread.
            log.warn("Outbox polling failed, will retry next cycle", ex);
        } finally {
            MDC.remove("batchId");
        }
    }

    private void processOne(Long sessionId, Long sequence) {
        // First transaction: apply + mark DONE. If apply() throws, we swallow the exception
        // inside the lambda so the outer transaction can still commit the rollback of the
        // projection upsert, and then hand off to a fresh transaction for the status UPDATE.
        ApplyFailure failure = eventProcessTemplate.execute(status -> {
            Event event = eventRepository.findBySessionIdAndSequence(sessionId, sequence).orElse(null);
            if (event == null || event.getProjectionStatus() != ProjectionStatus.PENDING) {
                return null;
            }
            try {
                projectionService.apply(event);
                eventRepository.updateProjectionStatus(
                        event.getSessionId(),
                        event.getSequence(),
                        ProjectionStatus.DONE.name(),
                        event.getRetryCount(),
                        event.getNextRetryAt(),
                        null
                );
                snapshotService.createSnapshotIfNeeded(event);
                return null;
            } catch (Exception ex) {
                status.setRollbackOnly();
                return new ApplyFailure(event, ex);
            }
        });

        if (failure == null) {
            return;
        }
        // Separate transaction so the status UPDATE is not discarded by the rolled-back parent.
        eventProcessTemplate.execute(status -> {
            handleFailure(failure.event(), failure.error());
            return null;
        });
    }

    private record ApplyFailure(Event event, Exception error) {
    }

    private void handleFailure(Event event, Exception ex) {
        int nextRetry = event.getRetryCount() + 1;
        if (nextRetry >= maxRetry) {
            moveToDeadLetter(event, ex);
            eventRepository.updateProjectionStatus(
                    event.getSessionId(),
                    event.getSequence(),
                    ProjectionStatus.FAILED.name(),
                    nextRetry,
                    event.getNextRetryAt(),
                    truncate(ex.getMessage(), 1024)
            );
            return;
        }
        long backoffSeconds = 1L << nextRetry; // 2, 4, 8, 16 seconds
        LocalDateTime nextAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        eventRepository.updateProjectionStatus(
                event.getSessionId(),
                event.getSequence(),
                ProjectionStatus.PENDING.name(),
                nextRetry,
                nextAt,
                truncate(ex.getMessage(), 1024)
        );
        log.warn("Projection apply failed (retry {}/{}): sessionId={}, sequence={}, error={}",
                nextRetry, maxRetry, event.getSessionId(), event.getSequence(), ex.getMessage());
    }

    private void moveToDeadLetter(Event event, Exception ex) {
        DeadLetterEvent dlq = DeadLetterEvent.builder()
                .originalEventId(event.getId())
                .sessionId(event.getSessionId())
                .sequence(event.getSequence())
                .eventType(event.getType().name())
                .payload(event.getPayload())
                .errorMessage(truncate(ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage(), 1024))
                .stackTrace(stackTraceOf(ex))
                .retryCount(event.getRetryCount() + 1)
                .build();
        deadLetterEventRepository.save(dlq);
        chatMetrics.incDeadLetter(ex.getClass().getSimpleName());
        log.error("Moved to DLQ: sessionId={}, sequence={}, retries={}, error={}",
                event.getSessionId(), event.getSequence(), event.getRetryCount() + 1, ex.getMessage());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
