package io.crewscope.application.inbox;

import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-resolved internal navigation target; it never accepts or stores an arbitrary URL. */
public record InboxSourceTarget(
        Kind kind,
        TeamId teamId,
        Optional<WorkProjectId> projectId,
        Optional<WorkItemId> workItemId,
        Optional<UUID> taskId,
        Optional<UUID> taskExecutionId,
        UUID sourceId) {

    public enum Kind {
        WORK_ITEM,
        REVIEW,
        ACTION,
        TASK,
        NOTIFICATION
    }

    public InboxSourceTarget {
        kind = Objects.requireNonNull(kind, "kind");
        teamId = Objects.requireNonNull(teamId, "teamId");
        projectId = Objects.requireNonNull(projectId, "projectId");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        if (projectId.isPresent() != workItemId.isPresent()) {
            throw new IllegalArgumentException("Project and WorkItem targets must be present together");
        }
        if ((kind == Kind.REVIEW || kind == Kind.ACTION || kind == Kind.TASK)
                && (taskId.isEmpty() || projectId.isEmpty())) {
            throw new IllegalArgumentException("Task-derived targets require WorkItem and Task scope");
        }
    }
}
