package io.crewscope.application.identity;

import java.util.Objects;

/** Public-safe account mutation failure without credential or identifier details. */
public final class CurrentAccountMutationException extends RuntimeException {

    private final CurrentAccountMutationFailure failure;

    public CurrentAccountMutationException(CurrentAccountMutationFailure failure) {
        super("Current account mutation could not be completed");
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public CurrentAccountMutationFailure failure() {
        return failure;
    }
}
