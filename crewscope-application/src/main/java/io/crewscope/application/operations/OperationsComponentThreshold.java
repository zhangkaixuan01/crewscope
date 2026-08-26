package io.crewscope.application.operations;

import java.time.Duration;
import java.util.Objects;

/** Deployment threshold for deriving one component's stable health classification. */
public record OperationsComponentThreshold(
        Duration degradedAfter,
        Duration attentionAfter,
        long degradedBacklog,
        long attentionBacklog) {

    public OperationsComponentThreshold {
        degradedAfter = requirePositive(degradedAfter, "degradedAfter");
        attentionAfter = requirePositive(attentionAfter, "attentionAfter");
        if (attentionAfter.compareTo(degradedAfter) < 0) {
            throw new IllegalArgumentException("attentionAfter must not precede degradedAfter");
        }
        if (degradedBacklog < 1 || attentionBacklog < degradedBacklog) {
            throw new IllegalArgumentException(
                    "backlog thresholds must be positive and monotonically increasing");
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name);
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return required;
    }
}
