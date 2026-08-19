package io.crewscope.infrastructure.workspace.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceKey;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cross-session command-call and evidence-sequence budget coverage for M4-I07. */
class SandboxCommandUsageM4I07Test {

    @Test
    void restoresEvidenceOrderAndSharesTheWorkspaceCommandBudgetAcrossSessions() {
        ExecutionWorkspace workspace = mock(ExecutionWorkspace.class);
        ExecutionWorkspaceId workspaceId = ExecutionWorkspaceId.generate();
        when(workspace.id()).thenReturn(workspaceId);
        when(workspace.workspaceKey()).thenReturn(ExecutionWorkspaceKey.derive(workspaceId, 1));
        when(workspace.taskExecutionId()).thenReturn(TaskExecutionId.generate());
        when(workspace.attempt()).thenReturn(1);
        WorkspacePolicy policy = mock(WorkspacePolicy.class);
        TaskFactHash policyHash = TaskFactHash.sha256("m4-i07-usage");
        when(policy.policyHash()).thenReturn(policyHash);
        when(policy.operationBudget()).thenReturn(new WorkspaceOperationBudget(
                3, 2, 1024, 2, 2048, 2048, 1));
        CommandEvidence existing = mock(CommandEvidence.class);
        when(existing.executionWorkspaceId()).thenReturn(workspaceId);
        when(existing.workspacePolicy()).thenReturn(new io.crewscope.domain.coding.WorkspacePolicyReference(
                io.crewscope.domain.coding.WorkspacePolicyId.generate(), policyHash));
        when(existing.sequence()).thenReturn(new EvidenceSequence(4));

        SandboxCommandUsageRegistry registry = new SandboxCommandUsageRegistry();
        SandboxCommandUsage first = registry.acquire(workspace, policy, List.of(existing));
        assertEquals(new EvidenceSequence(5), first.reserve().sequence());
        SandboxCommandUsage resumed = registry.acquire(workspace, policy, List.of(existing));
        assertEquals(new EvidenceSequence(6), resumed.reserve().sequence());

        SandboxCommandException exhausted = assertThrows(
                SandboxCommandException.class, resumed::reserve);
        assertEquals(SandboxCommandError.BUDGET_EXCEEDED, exhausted.error());
    }
}
