package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.EvidenceSequence;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.WorkspacePolicy;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Worker-held command count and next evidence sequence for one Workspace epoch. */
final class SandboxCommandUsage {

    private final io.crewscope.domain.coding.ExecutionWorkspaceId workspaceId;
    private final io.crewscope.domain.task.TaskExecutionId taskExecutionId;
    private final int attempt;
    private final io.crewscope.domain.task.TaskFactHash policyHash;
    private final int maximumCalls;
    private int calls;
    private EvidenceSequence nextSequence;

    SandboxCommandUsage(
            ExecutionWorkspace workspace,
            WorkspacePolicy policy,
            List<CommandEvidence> existingEvidence) {
        ExecutionWorkspace requiredWorkspace = Objects.requireNonNull(workspace, "workspace");
        WorkspacePolicy requiredPolicy = Objects.requireNonNull(policy, "policy");
        List<CommandEvidence> evidence = List.copyOf(existingEvidence);
        this.workspaceId = requiredWorkspace.id();
        this.taskExecutionId = requiredWorkspace.taskExecutionId();
        this.attempt = requiredWorkspace.attempt();
        this.policyHash = requiredPolicy.policyHash();
        this.maximumCalls = requiredPolicy.operationBudget().maxCommandCalls();
        evidence.forEach(item -> {
            if (!workspaceId.equals(item.executionWorkspaceId())
                    || !policyHash.equals(item.workspacePolicy().policyHash())) {
                throw failure(
                        SandboxCommandError.INVALID_CONTEXT,
                        "Existing command evidence does not match the current Workspace Policy");
            }
        });
        this.calls = evidence.size();
        this.nextSequence = evidence.stream()
                .map(CommandEvidence::sequence)
                .max(Comparator.naturalOrder())
                .map(EvidenceSequence::next)
                .orElseGet(EvidenceSequence::first);
        if (calls > maximumCalls) {
            throw failure(
                    SandboxCommandError.BUDGET_EXCEEDED,
                    "Workspace command budget is already exhausted");
        }
    }

    synchronized SandboxCommandUsage requireSame(
            ExecutionWorkspace workspace, WorkspacePolicy policy) {
        if (!workspaceId.equals(workspace.id())
                || !taskExecutionId.equals(workspace.taskExecutionId())
                || attempt != workspace.attempt()
                || !policyHash.equals(policy.policyHash())) {
            throw failure(
                    SandboxCommandError.INVALID_CONTEXT,
                    "Command usage belongs to a different Workspace or Policy");
        }
        return this;
    }

    synchronized Reservation reserve() {
        if (calls >= maximumCalls) {
            throw failure(
                    SandboxCommandError.BUDGET_EXCEEDED,
                    "Workspace command budget is exhausted");
        }
        calls++;
        EvidenceSequence reserved = nextSequence;
        nextSequence = nextSequence.next();
        return new Reservation(calls, maximumCalls, reserved);
    }

    record Reservation(int commandCalls, int maximumCommandCalls, EvidenceSequence sequence) {}

    private static SandboxCommandException failure(SandboxCommandError error, String message) {
        return new SandboxCommandException(error, message);
    }
}
