package io.crewscope.agentscope.coding;

import io.crewscope.application.coding.output.CodeChangeResultV1;
import java.util.Objects;

/** Strict final claim plus the same-call AgentState safe point for platform revalidation. */
public record CodingSpecialistRunResult(
        CodeChangeResultV1 output,
        CodingSpecialistStateSnapshot stateSnapshot,
        CodingSpecialistTelemetry telemetry) {

    public CodingSpecialistRunResult {
        output = Objects.requireNonNull(output, "output");
        stateSnapshot = Objects.requireNonNull(stateSnapshot, "stateSnapshot");
        telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }
}
