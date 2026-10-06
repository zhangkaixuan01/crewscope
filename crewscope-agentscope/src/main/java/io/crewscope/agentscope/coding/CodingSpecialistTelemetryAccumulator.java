package io.crewscope.agentscope.coding;

import io.agentscope.core.model.ChatUsage;
import io.crewscope.domain.model.ModelCallAttribution;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Call-scoped mutable collector; access is serialized by one AgentScope invocation. */
final class CodingSpecialistTelemetryAccumulator {

    private final List<CodingSpecialistModelUsage> modelUsages = new ArrayList<>();
    private final List<String> toolNames = new ArrayList<>();
    private final AtomicBoolean structuredOutputRequired = new AtomicBoolean();

    synchronized void recordModel(ChatUsage usage, ModelCallAttribution attribution) {
        modelUsages.add(CodingSpecialistModelUsage.from(usage, attribution));
    }

    synchronized void recordTools(List<String> names) {
        toolNames.addAll(names);
    }

    synchronized CodingSpecialistTelemetry snapshot() {
        return new CodingSpecialistTelemetry(modelUsages, toolNames);
    }

    void requireStructuredOutput() {
        structuredOutputRequired.set(true);
    }

    boolean structuredOutputRequired() {
        return structuredOutputRequired.get();
    }
}
