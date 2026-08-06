package io.crewscope.domain.shared.error;

import java.util.Objects;

/** Base exception for expected business failures that are safe to map across module boundaries. */
public class DomainException extends RuntimeException {

    private final DomainError error;

    protected DomainException(DomainError error) {
        super(Objects.requireNonNull(error, "error").message());
        this.error = error;
    }

    public final DomainError error() {
        return error;
    }
}
