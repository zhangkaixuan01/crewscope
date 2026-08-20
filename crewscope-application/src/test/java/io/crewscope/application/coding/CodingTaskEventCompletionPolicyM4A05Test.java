package io.crewscope.application.coding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.task.TaskStatus;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Prevents terminal Task SSE from racing the final Workspace event. */
class CodingTaskEventCompletionPolicyM4A05Test {

    @Test
    void waitsForCodingWorkspaceBoundaryButNotForNonCodingOrPreAllocationFailure() {
        WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(), WorkProjectId.generate());
        TaskId taskId = TaskId.generate();
        TaskExecutionId executionId = TaskExecutionId.generate();
        Task task = mock(Task.class);
        when(task.scope()).thenReturn(scope);
        when(task.id()).thenReturn(taskId);
        when(task.status()).thenReturn(TaskStatus.COMPLETED);
        when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
        CodingTargetSnapshotRepository targets = mock(CodingTargetSnapshotRepository.class);
        ExecutionWorkspaceRepository workspaces = mock(ExecutionWorkspaceRepository.class);
        CodingTaskEventCompletionPolicy policy =
                new CodingTaskEventCompletionPolicy(targets, workspaces);

        when(targets.findLatestByTask(
                        scope.organizationId(), scope.teamId(), scope.projectId(), taskId))
                .thenReturn(Optional.empty());
        assertTrue(policy.streamComplete(task));

        when(targets.findLatestByTask(
                        scope.organizationId(), scope.teamId(), scope.projectId(), taskId))
                .thenReturn(Optional.of(mock(CodingTargetSnapshot.class)));
        assertTrue(policy.streamComplete(task));

        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        when(workspaces.findByTaskExecution(
                        scope.organizationId(), scope.teamId(), scope.projectId(), executionId))
                .thenReturn(Optional.of(workspace));
        when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.FINALIZING);
        assertFalse(policy.streamComplete(task));

        when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.COMPLETED);
        assertTrue(policy.streamComplete(task));
    }
}
