package io.crewscope.agentscope.coding;

import java.util.List;
import java.util.Objects;

/** Content-free AgentScope telemetry captured during one finite Specialist round. */
public record CodingSpecialistTelemetry(
        List<CodingSpecialistModelUsage> modelUsages, List<String> toolNames) {

    public CodingSpecialistTelemetry {
        modelUsages = List.copyOf(Objects.requireNonNull(modelUsages, "modelUsages"));
        toolNames = List.copyOf(Objects.requireNonNull(toolNames, "toolNames"));
        toolNames.forEach(name -> {
            if (name == null || !name.matches("[a-z][a-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("toolNames must contain stable tool keys");
            }
        });
    }

    public static CodingSpecialistTelemetry none() {
        return new CodingSpecialistTelemetry(List.of(), List.of());
    }

    public int modelCalls() {
        return modelUsages.size();
    }

    public int toolCalls() {
        return toolNames.size();
    }
}
