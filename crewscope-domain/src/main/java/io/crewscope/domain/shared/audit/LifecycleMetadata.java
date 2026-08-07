package io.crewscope.domain.shared.audit;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Creation and modification timestamps for identity facts whose actor is recorded by events. */
public record LifecycleMetadata(UtcTimestamp createdAt, UtcTimestamp updatedAt) {

    public LifecycleMetadata {
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.compareTo(createdAt) < 0) {
            throw new DomainValidationException(
                    "lifecycle.updatedAt", "must not be before lifecycle.createdAt");
        }
    }

    public static LifecycleMetadata createdAt(UtcTimestamp occurredAt) {
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new LifecycleMetadata(requiredTime, requiredTime);
    }

    /** Advances the modification time while preventing out-of-order aggregate changes. */
    public LifecycleMetadata modifiedAt(UtcTimestamp occurredAt) {
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(updatedAt) < 0) {
            throw new DomainValidationException(
                    "lifecycle.updatedAt", "must not be before the current lifecycle.updatedAt");
        }
        return new LifecycleMetadata(createdAt, requiredTime);
    }
}
