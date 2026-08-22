package io.crewscope.agentscope.coding;

import java.util.Objects;

/** Internal failure wrapper that preserves redacted call telemetry without exposing model data. */
final class CodingSpecialistExecutionException extends RuntimeException {

    private final CodingSpecialistTelemetry telemetry;

    CodingSpecialistExecutionException(
            CodingSpecialistTelemetry telemetry, Throwable cause) {
        super("Coding Specialist AgentScope call failed", Objects.requireNonNull(cause, "cause"));
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    CodingSpecialistTelemetry telemetry() {
        return telemetry;
    }
}
