package io.crewscope.application.operations;

/** Ordered, low-cardinality health classification exposed outside the infrastructure layer. */
public enum OperationsHealthLevel {
    HEALTHY(0),
    DEGRADED(1),
    ATTENTION_REQUIRED(2),
    UNAVAILABLE(3);

    private final int severity;

    OperationsHealthLevel(int severity) {
        this.severity = severity;
    }

    public static OperationsHealthLevel worst(
            OperationsHealthLevel left, OperationsHealthLevel right) {
        return left.severity >= right.severity ? left : right;
    }
}
