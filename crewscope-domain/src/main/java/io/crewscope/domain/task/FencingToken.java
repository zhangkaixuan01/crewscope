package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Monotonically increasing ownership epoch that fences every stale Worker. */
public record FencingToken(long value) implements Comparable<FencingToken> {

    public FencingToken {
        if (value < 1) {
            throw new DomainValidationException("fencingToken", "must be positive");
        }
    }

    public static FencingToken initial() {
        return new FencingToken(1);
    }

    public FencingToken next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException("fencingToken", "must not overflow");
        }
        return new FencingToken(value + 1);
    }

    @Override
    public int compareTo(FencingToken other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
