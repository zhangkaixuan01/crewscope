package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Positive monotonic order of evidence within one ExecutionWorkspace. */
public record EvidenceSequence(long value) implements Comparable<EvidenceSequence> {

    public EvidenceSequence {
        if (value < 1) {
            throw new DomainValidationException("evidenceSequence", "must be positive");
        }
    }

    public static EvidenceSequence first() {
        return new EvidenceSequence(1);
    }

    public EvidenceSequence next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException("evidenceSequence", "has reached its maximum value");
        }
        return new EvidenceSequence(value + 1);
    }

    @Override
    public int compareTo(EvidenceSequence other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
