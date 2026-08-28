package io.crewscope.application.identity;

import java.util.Objects;

/** Fixed-cardinality telemetry boundary that cannot accept identity, network or Redis keys. */
public interface LoginDefenseTelemetry {

    void record(AuthenticationFlow flow, Operation operation, Outcome outcome);

    static LoginDefenseTelemetry noop() {
        return (flow, operation, outcome) -> {};
    }

    enum Operation {
        RESOURCE_ADMISSION,
        ACCOUNT_OBSERVE,
        ACCOUNT_FAILURE,
        ACCOUNT_SUCCESS
    }

    enum Outcome {
        ALLOWED,
        IDENTIFIER_LIMITED,
        NETWORK_LIMITED,
        BOTH_LIMITED,
        UNLOCKED,
        LOCKED,
        CLEARED,
        UNAVAILABLE
    }

    static void requireCoordinates(
            AuthenticationFlow flow, Operation operation, Outcome outcome) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(outcome, "outcome");
    }
}
