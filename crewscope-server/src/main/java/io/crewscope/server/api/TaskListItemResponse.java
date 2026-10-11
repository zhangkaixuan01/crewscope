package io.crewscope.server.api;

import io.crewscope.application.task.TaskListItem;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.task.TaskExecutionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One Task row for discovery lists; hides every runtime aggregate detail. */
public record TaskListItemResponse(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        UUID workItemId,
        String objective,
        List<String> acceptanceCriteria,
        String status,
        UUID currentExecutionId,
        Integer currentAttempt,
        String currentExecutionStatus,
        String currentWaitingReason,
        UUID ownerPrincipalId,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    static TaskListItemResponse from(TaskListItem value) {
        return new TaskListItemResponse(
                value.id().value(),
                value.scope().workspaceId().value(),
                value.scope().projectId().value(),
                value.workItemId().value(),
                value.brief().objective(),
                value.brief().acceptanceCriteria(),
                value.status().name(),
                value.currentExecutionId().map(TaskExecutionId::value).orElse(null),
                value.currentAttempt().orElse(null),
                value.currentExecutionStatus().map(Enum::name).orElse(null),
                value.currentWaitingReason().map(Enum::name).orElse(null),
                value.ownerPrincipalId().map(PrincipalId::value).orElse(null),
                value.version(),
                value.audit().createdAt().value(),
                value.audit().updatedAt().value());
    }
}
