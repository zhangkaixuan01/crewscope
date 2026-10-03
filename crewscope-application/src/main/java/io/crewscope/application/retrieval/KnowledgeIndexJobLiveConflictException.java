package io.crewscope.application.retrieval;

import java.util.Objects;

/**
 * A concurrent writer created the still-live job for the same target first
 * (M10-I01c). The persistence adapter translates the partial unique index
 * violation; enqueue callers catch it and converge onto the winner through the
 * find-live lookups, so a structural-idempotency race never surfaces as an error.
 */
public final class KnowledgeIndexJobLiveConflictException extends RuntimeException {

    private final String constraintName;

    public KnowledgeIndexJobLiveConflictException(String constraintName) {
        super("a concurrent writer already created the live job for this target ("
                + Objects.requireNonNull(constraintName, "constraintName") + ")");
        this.constraintName = constraintName;
    }

    /** The violated partial unique index, for logs only — never for control flow. */
    public String constraintName() {
        return constraintName;
    }
}
