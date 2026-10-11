package io.crewscope.server.api;

import io.crewscope.application.task.TaskDetails;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecutionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One Task with its pinned source, responsibility snapshot and attempt summaries. */
public record TaskDetailsResponse(
        UUID id,
        UUID teamId,
        UUID workspaceId,
        UUID projectId,
        UUID workItemId,
        String objective,
        List<String> acceptanceCriteria,
        TaskSourceResponse source,
        List<TaskResponsibilityResponse> responsibilitySnapshot,
        Instant responsibilityCapturedAt,
        String status,
        UUID currentExecutionId,
        TaskCancellationResponse cancellation,
        long version,
        AuditResponse audit,
        List<TaskExecutionResponse> attempts) {
    static TaskDetailsResponse from(TaskDetails details) {
        Task value = details.task();
        return new TaskDetailsResponse(
                value.id().value(),
                value.scope().teamId().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.workItemId().value(),
                value.brief().objective(),
                value.brief().acceptanceCriteria(),
                TaskSourceResponse.from(value.source()),
                value.responsibilitySnapshot().entries().stream()
                        .map(TaskResponsibilityResponse::from)
                        .toList(),
                value.responsibilitySnapshot().capturedAt().value(),
                value.status().name(),
                value.currentExecutionId().map(TaskExecutionId::value).orElse(null),
                value.cancellation().map(TaskCancellationResponse::from).orElse(null),
                value.version(),
                AuditResponse.from(value.audit()),
                details.attempts().stream().map(TaskExecutionResponse::from).toList());
    }
}
