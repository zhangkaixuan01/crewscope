package io.crewscope.server.api;

import io.crewscope.domain.task.PlanStep;
import java.util.List;

/** One plan step with its dependency and requirement keys in canonical order. */
public record PlanStepResponse(
        String key,
        int sequence,
        String title,
        String type,
        List<String> dependencyKeys,
        List<String> requiredCapabilities,
        List<String> requiredTools,
        boolean critical) {
    static PlanStepResponse from(PlanStep value) {
        return new PlanStepResponse(
                value.key(),
                value.sequence(),
                value.title(),
                value.type().name(),
                value.dependencyKeys().stream().sorted().toList(),
                value.requiredCapabilities().stream().map(Enum::name).sorted().toList(),
                value.requiredTools().stream().sorted().toList(),
                value.critical());
    }
}
