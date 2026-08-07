package io.crewscope.infrastructure.event;

import java.time.Duration;
import java.util.Objects;

/** Validated Outbox batch, lease, retry and parallelism settings. */
public record OutboxDeliveryPolicy(
        int batchSize,
        int maxAttempts,
        int parallelism,
        Duration claimLease,
        Duration initialBackoff,
        Duration maximumBackoff) {

    private static final Duration MINIMUM_DATABASE_DURATION = Duration.ofMillis(1);

    public OutboxDeliveryPolicy {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        claimLease = requireDatabaseDuration(claimLease, "claimLease");
        initialBackoff = requireDatabaseDuration(initialBackoff, "initialBackoff");
        maximumBackoff = requireDatabaseDuration(maximumBackoff, "maximumBackoff");
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be shorter than initialBackoff");
        }
    }

    /** Avoids claiming more leases than the publisher can start concurrently. */
    public int claimSize() {
        return Math.min(batchSize, parallelism);
    }

    private static Duration requireDatabaseDuration(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name);
        if (required.compareTo(MINIMUM_DATABASE_DURATION) < 0) {
            throw new IllegalArgumentException(name + " must be at least 1 millisecond");
        }
        return required;
    }
}
