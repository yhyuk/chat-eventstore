package com.example.chat.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class ChatMetrics {

    public static final String EVENTS_RECEIVED = "chat.events.received";
    public static final String EVENTS_DUPLICATES = "chat.events.duplicates";
    public static final String OUTBOX_PENDING_SIZE = "chat.outbox.pending.size";
    public static final String OUTBOX_LAG_SECONDS = "chat.outbox.lag.seconds";
    public static final String PROJECTION_DEAD_LETTER = "chat.projection.dead_letter";
    public static final String WEBSOCKET_SESSIONS_ACTIVE = "chat.websocket.sessions.active";

    private final MeterRegistry meterRegistry;

    // Micrometer는 게이지 소스를 WeakReference로 보관한다. 람다 supplier에 강한 참조가 없으면
    // GC 수집 후 NaN이 되므로, 이 리스트로 강한 참조를 유지한다.
    private final List<Supplier<Number>> gaugeSuppliers = new ArrayList<>();

    public void incEventsReceived(Object type, String result) {
        Counter.builder(EVENTS_RECEIVED)
                .tag("type", type == null ? "UNKNOWN" : type.toString())
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }

    public void incDuplicates() {
        Counter.builder(EVENTS_DUPLICATES)
                .register(meterRegistry)
                .increment();
    }

    public void incDeadLetter(String reason) {
        Counter.builder(PROJECTION_DEAD_LETTER)
                .tag("reason", reason == null ? "UNKNOWN" : reason)
                .register(meterRegistry)
                .increment();
    }

    public void registerPendingGauge(Supplier<Number> supplier) {
        gaugeSuppliers.add(supplier);
        Gauge.builder(OUTBOX_PENDING_SIZE, supplier, ChatMetrics::read)
                .description("Number of events in PENDING projection status")
                .register(meterRegistry);
    }

    public void registerLagGauge(Supplier<Number> supplier) {
        gaugeSuppliers.add(supplier);
        Gauge.builder(OUTBOX_LAG_SECONDS, supplier, ChatMetrics::read)
                .description("Seconds since the oldest PENDING event was received")
                .register(meterRegistry);
    }

    public void registerWsSessionsGauge(Supplier<Number> supplier) {
        gaugeSuppliers.add(supplier);
        Gauge.builder(WEBSOCKET_SESSIONS_ACTIVE, supplier, ChatMetrics::read)
                .description("Number of active WebSocket sessions")
                .register(meterRegistry);
    }

    private static double read(Supplier<Number> supplier) {
        Number v = supplier.get();
        return v == null ? 0.0 : v.doubleValue();
    }
}
