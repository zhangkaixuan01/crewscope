package io.crewscope.domain.task;

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
import io.crewscope.domain.coding.ExecutionWorkspaceId;
import io.crewscope.domain.coding.ExecutionWorkspaceRetention;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.coding.SandboxImageReference;
import io.crewscope.domain.coding.SandboxNetworkMode;
import io.crewscope.domain.coding.SandboxResourceBudget;
import io.crewscope.domain.coding.WorkspaceOperationBudget;
import io.crewscope.domain.coding.WorkspacePolicy;
import io.crewscope.domain.coding.WorkspacePolicyId;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared real aggregate graph for CommandEvidence and TestEvidence domain tests. */
final class CodingEvidenceFixture {

    static final UtcTimestamp TARGET_AT = UtcTimestamp.parse("2026-08-17T09:01:00Z");
    static final UtcTimestamp POLICY_AT = UtcTimestamp.parse("2026-08-17T09:02:00Z");
    static final UtcTimestamp READY_AT = UtcTimestamp.parse("2026-08-17T09:03:00Z");
    static final UtcTimestamp CLAIM_AT = UtcTimestamp.parse("2026-08-17T09:04:00Z");
    static final UtcTimestamp PREPARE_AT = UtcTimestamp.parse("2026-08-17T09:05:00Z");
    static final UtcTimestamp ALLOCATE_AT = UtcTimestamp.parse("2026-08-17T09:06:00Z");
    static final UtcTimestamp PROVISION_AT = UtcTimestamp.parse("2026-08-17T09:07:00Z");
    static final UtcTimestamp WORKTREE_READY_AT = UtcTimestamp.parse("2026-08-17T09:08:00Z");
    static final UtcTimestamp RUN_AT = UtcTimestamp.parse("2026-08-17T09:09:00Z");
    static final UtcTimestamp ACTIVE_AT = UtcTimestamp.parse("2026-08-17T09:10:00Z");
    static final UtcTimestamp EXPIRES_AT = UtcTimestamp.parse("2026-08-17T10:30:00Z");

    final TaskDomainFixture domain;
    final Task task;
    final CodingTargetSnapshot target;
    final BuildProfile profile;
    final TaskExecution execution;
    final TaskExecution preparingExecution;
    final TaskExecution runningExecution;
    final WorkspacePolicy policy;
    final ExecutionLease prepareLease;
    final ExecutionLease runLease;
    final ExecutionWorkspace workspace;

    private CodingEvidenceFixture(
            TaskDomainFixture domain,
            Task task,
            CodingTargetSnapshot target,
            BuildProfile profile,
            TaskExecution execution,
            TaskExecution preparingExecution,
            TaskExecution runningExecution,
            WorkspacePolicy policy,
            ExecutionLease prepareLease,
            ExecutionLease runLease,
            ExecutionWorkspace workspace) {
        this.domain = domain;
        this.task = task;
        this.target = target;
        this.profile = profile;
        this.execution = execution;
        this.preparingExecution = preparingExecution;
        this.runningExecution = runningExecution;
        this.policy = policy;
        this.prepareLease = prepareLease;
        this.runLease = runLease;
        this.workspace = workspace;
    }

    static CodingEvidenceFixture create() {
        TaskDomainFixture domain = new TaskDomainFixture();
        Task task = Task.create(
                TaskId.generate(),
                domain.workItem,
                TaskSource.fromWorkItem(domain.workItem),
                new TaskBrief(
                        "Verify immutable command evidence",
                        List.of("Unit tests pass", "Evidence is auditable")),
                domain.snapshot(),
                domain.owner,
                TaskDomainFixture.CREATED_AT);
        BuildProfile profile = BuildProfile.define(
                "maven-java-17",
                1,
                BuildTool.MAVEN_WRAPPER,
                17,
                new SandboxImageReference(
                        "eclipse-temurin:17-jdk@sha256:" + "a".repeat(64)),
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
        RepositoryBinding binding = RepositoryBinding.reconstitute(
                RepositoryBindingId.generate(),
                new RepositoryBindingScope(
                        task.scope().organizationId(),
                        task.scope().teamId(),
                        task.scope().workspaceId(),
                        task.scope().projectId()),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey("crewscope-java"),
                new RepositoryBranchName("main"),
                RepositoryBindingStatus.ACTIVE,
                1,
                AuditMetadata.createdBy(domain.owner.id(), TaskDomainFixture.CREATED_AT));
        CodingTargetSnapshot target = CodingTargetSnapshot.initial(
                CodingTargetSnapshotId.generate(),
                task,
                binding,
                new RepositoryBranchName("main"),
                new RepositoryCommitId("a".repeat(40)),
                CodingTargetAllowedPaths.of("crewscope-domain", "docs"),
                profile.reference(),
                domain.owner,
                TARGET_AT);
        TaskExecution rawExecution = TaskExecution.firstAttempt(
                TaskExecutionId.generate(),
                task,
                3,
                TaskExecutionPriority.NORMAL,
                TaskDomainFixture.CREATED_AT,
                domain.owner,
                TaskDomainFixture.CREATED_AT);
        PolicySnapshot policySnapshot = PolicySnapshot.initial(
                PolicySnapshotId.generate(),
                task,
                rawExecution,
                domain.executor,
                new PolicyPackReference(PolicyPackId.generate(), 1),
                AgentProfileId.generate(),
                1,
                Set.of(ExecutionCapability.SANDBOX, ExecutionCapability.WORKTREE),
                Set.of("command.mavenCompile", "command.mavenTest"),
                Set.of(),
                new PolicyBudget(120_000, 40, 160, 900),
                domain.owner,
                POLICY_AT);
        SafetyEnforcementOverlay safety = SafetyEnforcementOverlay.unrestricted(
                SafetyEnforcementOverlayId.generate(),
                task,
                rawExecution,
                domain.owner,
                POLICY_AT);
        TaskExecution execution = rawExecution.initializePlanningContext(
                policySnapshot, safety, 0, domain.owner, POLICY_AT);
        WorkspacePolicy policy = WorkspacePolicy.create(
                WorkspacePolicyId.generate(),
                target,
                execution,
                policySnapshot,
                profile,
                AllowedPathSet.of("crewscope-domain", "docs"),
                new SandboxResourceBudget(
                        SandboxNetworkMode.NONE, 2, 2_048, 256, 900, 1_048_576, true),
                new WorkspaceOperationBudget(
                        12, 20, 262_144, 80, 1_048_576, 524_288, 3),
                domain.owner,
                POLICY_AT);
        TaskExecution preparingExecution = execution.markReady(1, domain.owner, READY_AT)
                .claim(2, domain.owner, CLAIM_AT)
                .beginPreparing(3, domain.owner, PREPARE_AT);
        TaskExecution runningExecution = preparingExecution.beginRunning(4, domain.owner, RUN_AT);
        ExecutionLease prepareLease = lease(
                preparingExecution, ExecutionLeasePhase.PREPARE, 0, PREPARE_AT);
        ExecutionLease runLease = ExecutionLease.reconstitute(
                prepareLease.id(),
                prepareLease.organizationId(),
                prepareLease.environment(),
                prepareLease.taskExecutionId(),
                prepareLease.attempt(),
                prepareLease.runtimeId(),
                prepareLease.workerId(),
                prepareLease.claimTokenHash(),
                prepareLease.fencingToken(),
                ExecutionLeasePhase.RUN,
                prepareLease.acquiredAt(),
                RUN_AT,
                EXPIRES_AT,
                Optional.empty(),
                1);
        ExecutionWorkspace workspace = ExecutionWorkspace.allocate(
                        ExecutionWorkspaceId.generate(),
                        target,
                        preparingExecution,
                        prepareLease,
                        new ExecutionWorkspaceRetention(EXPIRES_AT),
                        domain.owner,
                        ALLOCATE_AT)
                .beginProvisioning(
                        preparingExecution, prepareLease, 0, domain.owner, PROVISION_AT)
                .markReady(
                        preparingExecution, prepareLease, 1, domain.owner, WORKTREE_READY_AT)
                .activate(runningExecution, runLease, 2, domain.owner, ACTIVE_AT);
        return new CodingEvidenceFixture(
                domain,
                task,
                target,
                profile,
                execution,
                preparingExecution,
                runningExecution,
                policy,
                prepareLease,
                runLease,
                workspace);
    }

    private static ExecutionLease lease(
            TaskExecution execution,
            ExecutionLeasePhase phase,
            int version,
            UtcTimestamp phaseStartedAt) {
        return ExecutionLease.reconstitute(
                ExecutionLeaseId.generate(),
                execution.scope().organizationId(),
                new RuntimeEnvironment("test"),
                execution.id(),
                execution.attempt(),
                ExecutionRuntimeId.generate(),
                RuntimeWorkerId.generate(),
                new ClaimTokenHash("d".repeat(64)),
                FencingToken.initial(),
                phase,
                PREPARE_AT,
                phaseStartedAt,
                EXPIRES_AT,
                Optional.empty(),
                version);
    }
}
