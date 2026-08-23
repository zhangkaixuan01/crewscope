package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Monotonic ownership epoch preventing a superseded Action Worker from committing. */
public record ActionFencingToken(long value) implements Comparable<ActionFencingToken> {

    public ActionFencingToken {
        if (value < 1) {
            throw new DomainValidationException("actionDispatch.fencingToken", "must be positive");
        }
    }

    public ActionFencingToken next() {
        if (value == Long.MAX_VALUE) {
            throw new DomainValidationException(
                    "actionDispatch.fencingToken", "has exhausted the supported range");
        }
        return new ActionFencingToken(value + 1);
    }

    @Override
    public int compareTo(ActionFencingToken other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
