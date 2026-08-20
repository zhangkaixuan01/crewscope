package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.infrastructure.persistence.coding.InMemoryWorkspaceWriteBudgetStore;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies exact write counters survive replacement Worker-local usage registries. */
class WorkspaceWriteBudgetM4A03Test {

    @Test
    void restoresReservationsAcrossWorkerRegistriesAndNeverRefundsRejectedWrites() {
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        ExecutionWorkspaceKey workspaceKey = ExecutionWorkspaceKey.derive(workspaceId, 1);
        TaskExecutionId executionId = TaskExecutionId.generate();
        TaskFactHash policyHash = TaskFactHash.sha256("m4-a03-budget");
        when(workspace.id()).thenReturn(workspaceId);
        when(workspace.workspaceKey()).thenReturn(workspaceKey);
        when(workspace.taskExecutionId()).thenReturn(executionId);
        when(workspace.attempt()).thenReturn(1);
        when(policy.policyHash()).thenReturn(policyHash);
        when(policy.operationBudget()).thenReturn(
                new WorkspaceOperationBudget(10, 2, 30, 2, 30, 1_000, 1));

        InMemoryWorkspaceWriteBudgetStore durable = new InMemoryWorkspaceWriteBudgetStore();
        CodingFilesystemUsage first = new CodingFilesystemUsageRegistry(durable)
                .acquire(workspace, policy, Set.of(), 0);
        assertEquals(1, first.reserve(Set.of("src/A.java"), 10).writeOperations());

        CodingFilesystemUsage recovered = new CodingFilesystemUsageRegistry(durable)
                .acquire(workspace, policy, Set.of("src/A.java"), 10);
        CodingFilesystemUsage.UsageSnapshot second =
                recovered.reserve(Set.of("src/B.java"), 20);
        assertEquals(2, second.writeOperations());
        assertEquals(30, second.writtenBytes());
        assertEquals(2, second.changedFiles());

        assertThrows(
                CodingFilesystemException.class,
                () -> recovered.reserve(Set.of("src/C.java"), 1));
        CodingFilesystemUsage.UsageSnapshot restored = new CodingFilesystemUsageRegistry(durable)
                .acquire(workspace, policy, Set.of(), 0)
                .snapshot();
        assertEquals(second, restored);
    }
}
