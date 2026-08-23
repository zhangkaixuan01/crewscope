package io.crewscope.domain.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Validated member-controlled supplement plus a non-expanded runtime Tool/Schema surface. */
public record AgentTemplateMemberConfiguration(
        Optional<String> supplementalInstructions,
        Set<AgentToolKey> enabledTools,
        Optional<AgentTemplateHash> structuredOutputSchemaHash) {

    public AgentTemplateMemberConfiguration {
        supplementalInstructions = Objects.requireNonNull(
                supplementalInstructions, "supplementalInstructions");
        enabledTools = Set.copyOf(Objects.requireNonNull(enabledTools, "enabledTools"));
        structuredOutputSchemaHash = Objects.requireNonNull(
                structuredOutputSchemaHash, "structuredOutputSchemaHash");
    }
}
