package io.crewscope.application.identity;

/** Signals that the bounded identity persistence executor cannot admit more blocking work. */
public final class IdentityPersistenceCapacityException extends RuntimeException {

    public IdentityPersistenceCapacityException() {
        super("Identity persistence capacity is exhausted");
    }
}
