package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Monotonic authority generation for one Workspace Diff projection. */
public record DiffGeneration(long value) implements Comparable<DiffGeneration> {

    public DiffGeneration {
        if (value < 1) {
            throw new DomainValidationException("diffGeneration", "must be positive");
        }
    }

    public static DiffGeneration first() {
        return new DiffGeneration(1);
    }

    public DiffGeneration next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException("diffGeneration", "has reached its maximum value");
        }
        return new DiffGeneration(value + 1);
    }

    @Override
    public int compareTo(DiffGeneration other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
