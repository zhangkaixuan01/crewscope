package io.crewscope.infrastructure.workspace.repository;

import java.util.Objects;

/** Path-free source repository validation failure. */
public final class ManagedDeliverySourceException extends RuntimeException {

    private final ManagedDeliverySourceError error;

    ManagedDeliverySourceException(ManagedDeliverySourceError error, String summary) {
        super(summary);
        this.error = Objects.requireNonNull(error, "error");
    }

    public ManagedDeliverySourceError error() {
        return error;
    }
}
