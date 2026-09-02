package io.crewscope.agentscope.task;

import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.TaskTokenAccessRequest;
import java.util.Objects;
import java.util.Set;

/** Validates AgentScope-emitted Task tools against the closed runtime allowlist and token scope. */
final class AgentScopeTaskToolPolicy {

    private AgentScopeTaskToolPolicy() {}

    static String requireAllowed(Set<String> allowedTools, String name) {
        Set<String> requiredAllowedTools = Objects.requireNonNull(allowedTools, "allowedTools");
        String required = requireText(name, "toolName", 128);
        if (!requiredAllowedTools.contains(required)) {
            throw new IllegalArgumentException("AgentScope emitted a forbidden Task Tool");
        }
        return required;
    }

    static void requireAuthorized(TaskExecutionRuntimeFacts facts, String toolName) {
        TaskExecutionRuntimeFacts requiredFacts = Objects.requireNonNull(facts, "facts");
        String requiredTool = requireText(toolName, "toolName", 128);
        if (!requiredTool.startsWith("fixture_")) {
            return;
        }
        requiredFacts.authorization().scope().requireAllowed(TaskTokenAccessRequest.tool(requiredTool));
        requiredFacts.stepExecution().ifPresent(step -> {
            PlanStep planStep = requiredFacts.planVersion().orElseThrow().steps().stream()
                    .filter(candidate -> candidate.key().equals(step.planStepKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Current Step is absent from the selected PlanVersion"));
            if (!planStep.requiredTools().contains(requiredTool)) {
                throw new IllegalArgumentException(
                        "Fixture Tool is outside the current Plan Step authorization");
            }
        });
    }

    private static String requireText(String value, String field, int maximumLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty() || required.length() > maximumLength
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains invalid text");
        }
        return required;
    }
}
