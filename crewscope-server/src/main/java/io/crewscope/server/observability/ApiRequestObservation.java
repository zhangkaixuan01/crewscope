package io.crewscope.server.observability;

/** Safe low-cardinality values shared by request logs and metrics. */
public record ApiRequestObservation(
        String method,
        String route,
        String status,
        String outcome,
        long durationNanos,
        String errorCode,
        String failureType) {

    /** Returns elapsed request time rounded down to milliseconds for structured logs. */
    public long durationMillis() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(durationNanos);
    }
}
