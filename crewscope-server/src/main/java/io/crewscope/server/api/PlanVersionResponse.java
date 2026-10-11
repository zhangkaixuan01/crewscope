package io.crewscope.server.api;

import io.crewscope.domain.task.PlanVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One published immutable plan version with its steps and todo summary. */
public record PlanVersionResponse(
        UUID id,
        long revision,
        UUID parentVersionId,
        String changeReason,
        String markdown,
        List<PlanStepResponse> steps,
        List<TodoResponse> todoSummary,
        UUID publishedByPrincipalId,
        Instant publishedAt) {
    static PlanVersionResponse from(PlanVersion value) {
        return new PlanVersionResponse(
                value.id().value(),
                value.revision(),
                value.parentVersionId().map(id -> id.value()).orElse(null),
                value.changeReason().name(),
                value.markdown(),
                value.steps().stream().map(PlanStepResponse::from).toList(),
                value.todoSummary().stream().map(TodoResponse::from).toList(),
                value.publishedByPrincipalId().value(),
                value.publishedAt().value());
    }
}
