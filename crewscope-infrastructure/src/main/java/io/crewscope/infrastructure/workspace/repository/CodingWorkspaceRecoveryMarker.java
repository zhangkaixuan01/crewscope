package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.application.coding.CodingTaskTimelinePublisher;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.infrastructure.runtime.TaskExecutionRecoveryObserver;
import java.util.Objects;

/** Atomically couples M3 Task recovery to the associated M4 Workspace recovery generation. */
public final class CodingWorkspaceRecoveryMarker implements TaskExecutionRecoveryObserver {

    private final ExecutionWorkspaceRepository workspaces;
    private final Principal actor;
    private final CodingTaskTimelinePublisher timeline;

    public CodingWorkspaceRecoveryMarker(
            ExecutionWorkspaceRepository workspaces, Principal actor) {
        this(workspaces, actor, CodingTaskTimelinePublisher.NO_OP);
    }

    public CodingWorkspaceRecoveryMarker(
            ExecutionWorkspaceRepository workspaces,
            Principal actor,
            CodingTaskTimelinePublisher timeline) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    @Override
    public void beforeRequeue(TaskExecution execution, UtcTimestamp authoritativeNow) {
        TaskExecution recovering = Objects.requireNonNull(execution, "execution");
        workspaces.findByTaskExecutionForUpdate(
                        recovering.scope().organizationId(),
                        recovering.scope().teamId(),
                        recovering.scope().projectId(),
                        recovering.id())
                .filter(CodingWorkspaceRecoveryMarker::requiresRecovery)
                .map(workspace -> workspace.beginRecovery(
                        recovering,
                        workspace.version(),
                        actor,
                        Objects.requireNonNull(authoritativeNow, "authoritativeNow")))
                .ifPresent(changed -> {
                    ExecutionWorkspace committed = workspaces.update(changed);
                    timeline.workspaceChanged(committed);
                });
    }

    private static boolean requiresRecovery(ExecutionWorkspace workspace) {
        return workspace.status() == ExecutionWorkspaceStatus.PROVISIONING
                || workspace.status() == ExecutionWorkspaceStatus.READY
                || workspace.status() == ExecutionWorkspaceStatus.ACTIVE
                || workspace.status() == ExecutionWorkspaceStatus.FINALIZING;
    }
}
