package io.crewscope.server.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local aggregate for telemetry work discarded by a failing observability backend. */
public final class TelemetryDegradationState {

    enum FailureType {
        TRACE,
        METRIC,
        LOG,
        BAGGAGE
    }

    private final EnumMap<FailureType, AtomicLong> dropped = new EnumMap<>(FailureType.class);

    public TelemetryDegradationState() {
        for (FailureType type : FailureType.values()) {
            dropped.put(type, new AtomicLong());
        }
    }

    void record(FailureType type) {
        dropped.get(type).incrementAndGet();
    }

    long count(FailureType type) {
        return dropped.get(type).get();
    }

    long total() {
        return dropped.values().stream().mapToLong(AtomicLong::get).sum();
    }

    Map<String, Long> snapshot() {
        EnumMap<FailureType, Long> values = new EnumMap<>(FailureType.class);
        dropped.forEach((type, count) -> values.put(type, count.get()));
        return Map.of(
                "trace", values.get(FailureType.TRACE),
                "metric", values.get(FailureType.METRIC),
                "log", values.get(FailureType.LOG),
                "baggage", values.get(FailureType.BAGGAGE));
    }
}
