package io.crewscope.application.coding;

import io.crewscope.application.task.TaskEventCompletionPolicy;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.task.Task;
import java.util.Objects;

/** Keeps a terminal Coding Task stream open until its Workspace reaches a durable boundary. */
public final class CodingTaskEventCompletionPolicy implements TaskEventCompletionPolicy {

    private final CodingTargetSnapshotRepository targets;
    private final ExecutionWorkspaceRepository workspaces;

    public CodingTaskEventCompletionPolicy(
            CodingTargetSnapshotRepository targets,
            ExecutionWorkspaceRepository workspaces) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    @Override
    public boolean streamComplete(Task task) {
        Task value = Objects.requireNonNull(task, "task");
        if (!TaskEventCompletionPolicy.TASK_TERMINAL.streamComplete(value)) {
            return false;
        }
        boolean coding = targets.findLatestByTask(
                        value.scope().organizationId(),
                        value.scope().teamId(),
                        value.scope().projectId(),
                        value.id())
                .isPresent();
        if (!coding || value.currentExecutionId().isEmpty()) {
            return true;
        }
        return workspaces.findByTaskExecution(
                        value.scope().organizationId(),
                        value.scope().teamId(),
                        value.scope().projectId(),
                        value.currentExecutionId().orElseThrow())
                .map(workspace -> workspace.status() == ExecutionWorkspaceStatus.COMPLETED
                        || workspace.status() == ExecutionWorkspaceStatus.FAILED
                        || workspace.status() == ExecutionWorkspaceStatus.ARCHIVED)
                // Preparation may fail before a Workspace is allocated; the terminal Task then
                // remains the complete authority and must not leave SSE open forever.
                .orElse(true);
    }
}
