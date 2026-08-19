package io.crewscope.infrastructure.workspace.repository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.ExecutionWorkspaceRepository;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves Workspace RECOVERING is committed before M3 requeues its TaskExecution. */
class CodingWorkspaceRecoveryMarkerM4I10Test {

    @Test
    void startsExactlyOneRecoveryGenerationForAnInterruptedWorkspace() {
        ExecutionWorkspaceRepository repository = mock(ExecutionWorkspaceRepository.class);
        Principal actor = mock(Principal.class);
        TaskExecution execution = mock(TaskExecution.class);
        TaskExecutionId executionId = TaskExecutionId.generate();
        WorkItemScope scope = new WorkItemScope(
                OrganizationId.generate(),
                TeamId.generate(),
                WorkspaceId.generate(),
                WorkProjectId.generate());
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        ExecutionWorkspace recovering = mock(ExecutionWorkspace.class);
        UtcTimestamp now = UtcTimestamp.parse("2026-08-19T04:00:00Z");
        when(execution.id()).thenReturn(executionId);
        when(execution.scope()).thenReturn(scope);
        when(repository.findByTaskExecutionForUpdate(
                        scope.organizationId(), scope.teamId(), scope.projectId(), executionId))
                .thenReturn(Optional.of(workspace));
        when(workspace.status()).thenReturn(ExecutionWorkspaceStatus.ACTIVE);
        when(workspace.version()).thenReturn(5L);
        when(workspace.beginRecovery(execution, 5L, actor, now)).thenReturn(recovering);

        new CodingWorkspaceRecoveryMarker(repository, actor).beforeRequeue(execution, now);

        verify(repository).update(recovering);
    }
}
