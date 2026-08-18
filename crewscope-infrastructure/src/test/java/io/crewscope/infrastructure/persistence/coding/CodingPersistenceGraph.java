package io.crewscope.infrastructure.persistence.coding;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
import io.crewscope.domain.coding.ExecutionWorkspace;
import io.crewscope.domain.coding.ExecutionWorkspaceCompletionReason;
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceRetention;
import io.crewscope.domain.coding.ExecutionWorkspaceStatus;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ClaimTokenHash;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.ExecutionLeasePhase;
import io.crewscope.domain.task.FencingToken;
import io.crewscope.domain.task.PolicyBudget;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Builds hash-valid M4 aggregates while leaving upstream M3 facts to the SQL test fixture. */
final class CodingPersistenceGraph {

    static final UtcTimestamp TARGET_AT = UtcTimestamp.parse("2026-08-18T01:03:00Z");
    static final UtcTimestamp POLICY_AT = UtcTimestamp.parse("2026-08-18T01:04:00Z");
    static final UtcTimestamp ACQUIRED_AT = UtcTimestamp.parse("2026-08-18T01:05:00Z");
    static final UtcTimestamp WORKSPACE_AT = UtcTimestamp.parse("2026-08-18T01:06:00Z");
    static final UtcTimestamp RETAIN_UNTIL = UtcTimestamp.parse("2026-08-18T04:00:00Z");

    final M4D09CodingPersistenceIntegrationTest.Fixture fixture;
    final RepositoryBinding binding;
    final TaskId taskId = TaskId.generate();
    final TaskExecutionId taskExecutionId = TaskExecutionId.generate();
    final PolicySnapshotId policySnapshotId = PolicySnapshotId.generate();
    final ExecutionRuntimeId runtimeId = ExecutionRuntimeId.generate();
    final RuntimeWorkerId workerId = RuntimeWorkerId.generate();
    final ExecutionLeaseId leaseId = ExecutionLeaseId.generate();
    final PrincipalId agentPrincipalId = PrincipalId.generate();
    final AgentProfileId agentProfileId = AgentProfileId.generate();
    final UUID runtimeSessionId = UUID.randomUUID();
    final AgentRunId agentRunId = AgentRunId.generate();
    final BuildProfile buildProfile;
    final CodingTargetSnapshot target;
    final WorkspacePolicy policy;
    final ExecutionLease lease;
    final ExecutionWorkspace pendingWorkspace;
    final ExecutionWorkspace activeWorkspace;
    final ExecutionWorkspace finalizingWorkspace;

    private CodingPersistenceGraph(
            M4D09CodingPersistenceIntegrationTest.Fixture fixture,
            RepositoryBinding binding) {
        this.fixture = fixture;
        this.binding = binding;
        this.buildProfile = buildProfile();
        this.target = target();
        PolicyFacts policyFacts = policyFacts();
        this.policy = policy(policyFacts);
        this.lease = lease();
        this.pendingWorkspace = pendingWorkspace(policyFacts.execution());
        this.activeWorkspace = workspaceWithStatus(
                pendingWorkspace, ExecutionWorkspaceStatus.ACTIVE, Optional.empty());
        this.finalizingWorkspace = workspaceWithStatus(
                pendingWorkspace,
                ExecutionWorkspaceStatus.FINALIZING,
                Optional.of(ExecutionWorkspaceCompletionReason.SUCCEEDED));
    }

    static CodingPersistenceGraph create(
            M4D09CodingPersistenceIntegrationTest.Fixture fixture,
            RepositoryBinding binding) {
        return new CodingPersistenceGraph(fixture, binding);
    }

    private BuildProfile buildProfile() {
        return BuildProfile.define(
                "maven-java-17",
                1,
                BuildTool.MAVEN_WRAPPER,
                17,
                new SandboxImageReference("eclipse-temurin:17-jdk@sha256:" + "a".repeat(64)),
                new CommandCatalog(Map.of(
                        CommandKind.COMPILE,
                        new BuildCommand(
                                "command.mavenCompile",
                                List.of("./mvnw", "compile"),
                                ".",
                                60,
                                600),
                        CommandKind.TEST,
                        new BuildCommand(
                                "command.mavenTest",
                                List.of("./mvnw", "test"),
                                ".",
                                60,
                                900))));
    }

    private CodingTargetSnapshot target() {
        Task task = mock(Task.class);
        when(task.scope()).thenReturn(fixture.workItemScope());
        when(task.id()).thenReturn(taskId);
        when(task.status()).thenReturn(io.crewscope.domain.task.TaskStatus.CREATED);
        when(task.currentExecutionId()).thenReturn(Optional.empty());
        when(task.brief()).thenReturn(new TaskBrief(
                "Persist a Coding execution",
                List.of("Tests pass", "Evidence remains auditable")));
        return CodingTargetSnapshot.initial(
                CodingTargetSnapshotId.generate(),
                task,
                binding,
                binding.defaultBranch(),
                new RepositoryCommitId("a".repeat(40)),
                CodingTargetAllowedPaths.of("crewscope-domain", "docs"),
                buildProfile.reference(),
                fixture.actor(),
                TARGET_AT);
    }

    private PolicyFacts policyFacts() {
        PolicySnapshot snapshot = mock(PolicySnapshot.class);
        TaskFactHash snapshotHash = TaskFactHash.sha256("m4-d09-policy");
        when(snapshot.id()).thenReturn(policySnapshotId);
        when(snapshot.snapshotHash()).thenReturn(snapshotHash);
        when(snapshot.scope()).thenReturn(fixture.workItemScope());
        when(snapshot.taskId()).thenReturn(taskId);
        when(snapshot.executionId()).thenReturn(taskExecutionId);
        when(snapshot.capabilities()).thenReturn(Set.of(
                ExecutionCapability.SANDBOX, ExecutionCapability.WORKTREE));
        when(snapshot.allowedTools()).thenReturn(buildProfile.commandCatalog().toolKeys());
        when(snapshot.budget()).thenReturn(new PolicyBudget(120_000, 40, 160, 900));

        TaskExecutionPlanningContext planning = mock(TaskExecutionPlanningContext.class);
        when(planning.policySnapshotId()).thenReturn(policySnapshotId);
        when(planning.policySnapshotHash()).thenReturn(snapshotHash);
        TaskExecution execution = mock(TaskExecution.class);
        when(execution.scope()).thenReturn(fixture.workItemScope());
        when(execution.taskId()).thenReturn(taskId);
        when(execution.id()).thenReturn(taskExecutionId);
        when(execution.attempt()).thenReturn(1);
        when(execution.status()).thenReturn(TaskExecutionStatus.PREPARING);
        when(execution.lastFencingToken()).thenReturn(Optional.of(FencingToken.initial()));
        when(execution.planningContext()).thenReturn(Optional.of(planning));
        return new PolicyFacts(execution, snapshot);
    }

    private WorkspacePolicy policy(PolicyFacts facts) {
        return WorkspacePolicy.create(
                WorkspacePolicyId.generate(),
                target,
                facts.execution(),
                facts.snapshot(),
                buildProfile,
                AllowedPathSet.of("crewscope-domain", "docs"),
                new SandboxResourceBudget(
                        SandboxNetworkMode.NONE, 2, 2_048, 256, 900, 1_048_576, true),
                new WorkspaceOperationBudget(
                        12, 20, 262_144, 80, 1_048_576, 524_288, 3),
                fixture.actor(),
                POLICY_AT);
    }

    private ExecutionLease lease() {
        return ExecutionLease.reconstitute(
                leaseId,
                fixture.organizationId(),
                new RuntimeEnvironment("test"),
                taskExecutionId,
                1,
                runtimeId,
                workerId,
                new ClaimTokenHash("d".repeat(64)),
                FencingToken.initial(),
                ExecutionLeasePhase.PREPARE,
                ACQUIRED_AT,
                ACQUIRED_AT,
                RETAIN_UNTIL,
                Optional.empty(),
                0);
    }

    private ExecutionWorkspace pendingWorkspace(TaskExecution execution) {
        return ExecutionWorkspace.allocate(
                ExecutionWorkspaceId.generate(),
                target,
                execution,
                lease,
                new ExecutionWorkspaceRetention(RETAIN_UNTIL),
                fixture.actor(),
                WORKSPACE_AT);
    }

    private static ExecutionWorkspace workspaceWithStatus(
            ExecutionWorkspace value,
            ExecutionWorkspaceStatus status,
            Optional<ExecutionWorkspaceCompletionReason> completionReason) {
        return ExecutionWorkspace.reconstitute(
                value.id(),
                value.scope(),
                value.taskId(),
                value.taskExecutionId(),
                value.attempt(),
                value.codingTarget(),
                value.repositoryBindingId(),
                value.repositoryBindingVersion(),
                value.repositoryKey(),
                value.baselineCommit(),
                value.workspaceKey(),
                value.managedBranch(),
                value.archiveReference(),
                value.ownership(),
                status,
                Optional.empty(),
                value.recoveryGeneration(),
                completionReason,
                Optional.empty(),
                value.retention(),
                value.fingerprint(),
                0,
                value.audit());
    }

    private record PolicyFacts(TaskExecution execution, PolicySnapshot snapshot) {}
}
