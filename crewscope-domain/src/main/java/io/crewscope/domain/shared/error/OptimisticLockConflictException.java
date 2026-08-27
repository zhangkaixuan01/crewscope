package io.crewscope.domain.shared.error;

import io.crewscope.domain.shared.id.AggregateId;
import java.util.Map;
import java.util.Objects;

/** Reports that a command expected an aggregate version different from the committed version. */
public final class OptimisticLockConflictException extends DomainException {

    public OptimisticLockConflictException(
            String aggregateType, AggregateId aggregateId, long expectedVersion, long actualVersion) {
        this(aggregateType, Objects.requireNonNull(aggregateId, "aggregateId").toString(),
                expectedVersion, actualVersion);
    }

    /**
     * Reports a conflict for a versioned resource whose canonical identity is not a UUID aggregate.
     */
    public OptimisticLockConflictException(
            String aggregateType, String aggregateId, long expectedVersion, long actualVersion) {
        super(new DomainError(
                DomainErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                "%s %s version conflict: expected %d, actual %d"
                        .formatted(
                                requireAggregateType(aggregateType),
                                requireAggregateId(aggregateId),
                                requireVersion(expectedVersion, "expectedVersion"),
                                requireVersion(actualVersion, "actualVersion")),
                Map.of(
                        "aggregateType", aggregateType.strip(),
                        "aggregateId", aggregateId.strip(),
                        "expectedVersion", Long.toString(expectedVersion),
                        "actualVersion", Long.toString(actualVersion))));
    }

    private static String requireAggregateType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        return value.strip();
    }

    private static String requireAggregateId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("aggregateId must not be blank");
        }
        return value.strip();
    }

    private static long requireVersion(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
