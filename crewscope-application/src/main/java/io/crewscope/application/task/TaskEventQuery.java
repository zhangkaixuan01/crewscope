package io.crewscope.application.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;

/** Scope-bound keyset query for one Task Event stream. */
public record TaskEventQuery(
        WorkItemScope scope,
        TaskId taskId,
        Optional<TaskEventCursor> cursor,
        int limit) {

    public TaskEventQuery {
        scope = Objects.requireNonNull(scope, "scope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        cursor = Objects.requireNonNull(cursor, "cursor");
        WorkItemScope requiredScope = scope;
        TaskId requiredTaskId = taskId;
        cursor.ifPresent(value -> value.requireStream(
                requiredScope.organizationId(), requiredScope.teamId(), requiredTaskId));
        if (limit < 1 || limit > 100) {
            throw new DomainValidationException(
                    "taskEventQuery.limit", "must be between 1 and 100");
        }
    }
}
