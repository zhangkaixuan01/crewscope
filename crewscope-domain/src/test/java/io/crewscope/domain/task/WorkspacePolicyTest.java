package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.coding.AllowedPathSet;
import io.crewscope.domain.coding.BuildCommand;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildTool;
import io.crewscope.domain.coding.CodingTargetAllowedPaths;
import io.crewscope.domain.coding.CodingTargetSnapshot;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CommandCatalog;
import io.crewscope.domain.coding.CommandKind;
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
import io.crewscope.domain.coding.WorkspacePolicyOverlay;
import io.crewscope.domain.coding.WorkspacePolicyOverlayId;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspacePolicyTest {

    private static final UtcTimestamp TARGET_AT = UtcTimestamp.parse("2026-08-17T08:10:00Z");
    private static final UtcTimestamp POLICY_AT = UtcTimestamp.parse("2026-08-17T08:20:00Z");
    private static final UtcTimestamp OVERLAY_AT = UtcTimestamp.parse("2026-08-17T08:30:00Z");

    @Test
    void closesTargetExecutionPolicyBuildProfileAndCanonicalHash() {
        Fixture fixture = Fixture.create();

        WorkspacePolicy workspacePolicy = fixture.workspacePolicy();

        assertEquals(fixture.target.reference(), workspacePolicy.codingTarget());
        assertEquals(fixture.execution.id(), workspacePolicy.taskExecutionId());
        assertEquals(1, workspacePolicy.attempt());
        assertEquals(fixture.policy.id(), workspacePolicy.policySnapshotId());
        assertEquals(fixture.policy.snapshotHash(), workspacePolicy.policySnapshotHash());
        assertEquals(fixture.profile.reference(), workspacePolicy.buildProfile());
        assertEquals(Set.of("command.mavenTest", "command.mavenVerify"),
                workspacePolicy.commandCatalog().toolKeys());
        assertEquals(64, workspacePolicy.policyHash().value().length());
        assertEquals(
                workspacePolicy.policyHash(),
                reconstitute(workspacePolicy, workspacePolicy.policyHash()).policyHash());
    }

    @Test
    void rejectsPathsOutsideTheCapturedCodingTarget() {
        Fixture fixture = Fixture.create();

        assertThrows(
                DomainValidationException.class,
                () -> fixture.workspacePolicy(AllowedPathSet.of("scripts"), fixture.policy));
    }

    @Test
    void rejectsAnUninitializedOrDifferentCurrentPolicySnapshot() {
        Fixture fixture = Fixture.create();
        TaskExecution uninitialized = TaskExecution.firstAttempt(
                TaskExecutionId.generate(),
                fixture.task,
                3,
                TaskExecutionPriority.NORMAL,
                TaskDomainFixture.CREATED_AT,
                fixture.domain.owner,
                TaskDomainFixture.CREATED_AT);

        assertThrows(
                DomainValidationException.class,
                () -> WorkspacePolicy.create(
                        WorkspacePolicyId.generate(),
                        fixture.target,
                        uninitialized,
                        fixture.policy,
                        fixture.profile,
                        fixture.paths,
                        fixture.sandbox,
                        fixture.operations,
                        fixture.domain.owner,
                        POLICY_AT));
    }

    @Test
    void rejectsMissingCapabilitiesToolsAndBroaderBaseBudgets() {
        Fixture fixture = Fixture.create();
        PolicySnapshot insufficient = fixture.policy(
                Set.of(ExecutionCapability.SANDBOX),
                Set.of("command.mavenTest"),
                new PolicyBudget(120_000, 40, 160, 900));
        SafetyEnforcementOverlay safety = SafetyEnforcementOverlay.unrestricted(
                SafetyEnforcementOverlayId.generate(),
                fixture.task,
                fixture.rawExecution,
                fixture.domain.owner,
                POLICY_AT);
        TaskExecution execution = fixture.rawExecution.initializePlanningContext(
                insufficient, safety, 0, fixture.domain.owner, POLICY_AT);

        assertThrows(
                DomainValidationException.class,
                () -> WorkspacePolicy.create(
                        WorkspacePolicyId.generate(),
                        fixture.target,
                        execution,
                        insufficient,
                        fixture.profile,
                        fixture.paths,
                        fixture.sandbox,
                        fixture.operations,
                        fixture.domain.owner,
                        POLICY_AT));

        WorkspaceOperationBudget tooManyCalls = new WorkspaceOperationBudget(
                100, 20, 262_144, 80, 1_048_576, 524_288, 3);
        assertThrows(
                DomainValidationException.class,
                () -> fixture.workspacePolicy(fixture.paths, fixture.policy, fixture.sandbox, tooManyCalls));
    }

    @Test
    void rejectsNetworkWritableRootCommandTimeoutAndTamperedHash() {
        Fixture fixture = Fixture.create();

        assertThrows(
                DomainValidationException.class,
                () -> fixture.workspacePolicy(
                        fixture.paths,
                        fixture.policy,
                        new SandboxResourceBudget(
                                SandboxNetworkMode.LOOPBACK_ONLY,
                                2,
                                2_048,
                                256,
                                900,
                                1_048_576,
                                true),
                        fixture.operations));
        assertThrows(
                DomainValidationException.class,
                () -> fixture.workspacePolicy(
                        fixture.paths,
                        fixture.policy,
                        new SandboxResourceBudget(
                                SandboxNetworkMode.NONE,
                                2,
                                2_048,
                                256,
                                600,
                                1_048_576,
                                true),
                        fixture.operations));
        WorkspacePolicy policy = fixture.workspacePolicy();
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(policy, TaskFactHash.sha256("tampered")));
    }

    @Test
    void createsUnrestrictedOverlayThenNarrowsPathsCommandsAndEveryBudgetFamily() {
        Fixture fixture = Fixture.create();
        WorkspacePolicy policy = fixture.workspacePolicy();
        WorkspacePolicyOverlay initial = WorkspacePolicyOverlay.unrestricted(
                WorkspacePolicyOverlayId.generate(), policy, fixture.domain.owner, POLICY_AT);
        CommandCatalog testOnly = CommandCatalog.of(
                CommandKind.TEST,
                fixture.profile.commandCatalog().commands().get(CommandKind.TEST));

        WorkspacePolicyOverlay tightened = initial.tighten(
                policy,
                AllowedPathSet.of("crewscope-domain/src"),
                testOnly,
                new SandboxResourceBudget(
                        SandboxNetworkMode.NONE, 1, 1_024, 128, 600, 524_288, true),
                new WorkspaceOperationBudget(10, 10, 131_072, 40, 524_288, 262_144, 2),
                fixture.domain.owner,
                OVERLAY_AT);

        assertEquals(2, tightened.version());
        assertEquals(Optional.of(initial.overlayHash()), tightened.parentOverlayHash());
        assertEquals(List.of("crewscope-domain/src"), tightened.allowedPaths().values());
        assertEquals(Set.of("command.mavenTest"), tightened.commandCatalog().toolKeys());
        assertNotEquals(initial.overlayHash(), tightened.overlayHash());
        assertEquals(
                tightened.overlayHash(),
                WorkspacePolicyOverlay.reconstitute(
                                tightened.id(),
                                policy,
                                tightened.version(),
                                tightened.parentOverlayHash(),
                                tightened.allowedPaths(),
                                tightened.commandCatalog(),
                                tightened.sandboxBudget(),
                                tightened.operationBudget(),
                                tightened.overlayHash(),
                                tightened.audit())
                        .overlayHash());
    }

    @Test
    void rejectsOverlayExpansionCommandReplacementAndNoopSuccessors() {
        Fixture fixture = Fixture.create();
        WorkspacePolicy policy = fixture.workspacePolicy();
        WorkspacePolicyOverlay overlay = WorkspacePolicyOverlay.unrestricted(
                WorkspacePolicyOverlayId.generate(), policy, fixture.domain.owner, POLICY_AT);
        BuildCommand replacement = new BuildCommand(
                "command.mavenTest", List.of("./mvnw", "test", "-DskipITs"), ".", 60, 900);
        CommandCatalog replacedCatalog = new CommandCatalog(Map.of(
                CommandKind.TEST, replacement,
                CommandKind.VERIFY,
                        fixture.profile.commandCatalog().commands().get(CommandKind.VERIFY)));

        assertThrows(
                DomainValidationException.class,
                () -> overlay.tighten(
                        policy,
                        AllowedPathSet.of("."),
                        policy.commandCatalog(),
                        policy.sandboxBudget(),
                        policy.operationBudget(),
                        fixture.domain.owner,
                        OVERLAY_AT));
        WorkspacePolicyOverlay commandsDisabled = overlay.tighten(
                policy,
                policy.allowedPaths(),
                new CommandCatalog(Map.of()),
                policy.sandboxBudget(),
                policy.operationBudget(),
                fixture.domain.owner,
                OVERLAY_AT);
        assertTrue(commandsDisabled.commandCatalog().commands().isEmpty());
        assertThrows(
                DomainValidationException.class,
                () -> overlay.tighten(
                        policy,
                        policy.allowedPaths(),
                        replacedCatalog,
                        policy.sandboxBudget(),
                        policy.operationBudget(),
                        fixture.domain.owner,
                        OVERLAY_AT));
        assertThrows(
                DomainValidationException.class,
                () -> overlay.tighten(
                        policy,
                        policy.allowedPaths(),
                        policy.commandCatalog(),
                        policy.sandboxBudget(),
                        policy.operationBudget(),
                        fixture.domain.owner,
                        OVERLAY_AT));
    }

    @Test
    void rejectsOverlayAgainstAnotherBasePolicyOrTamperedPersistence() {
        Fixture fixture = Fixture.create();
        WorkspacePolicy policy = fixture.workspacePolicy();
        WorkspacePolicy another = fixture.workspacePolicy();
        WorkspacePolicyOverlay overlay = WorkspacePolicyOverlay.unrestricted(
                WorkspacePolicyOverlayId.generate(), policy, fixture.domain.owner, POLICY_AT);

        assertThrows(
                DomainValidationException.class,
                () -> overlay.tighten(
                        another,
                        AllowedPathSet.of("crewscope-domain/src"),
                        policy.commandCatalog(),
                        policy.sandboxBudget(),
                        policy.operationBudget(),
                        fixture.domain.owner,
                        OVERLAY_AT));
        assertThrows(
                DomainValidationException.class,
                () -> WorkspacePolicyOverlay.reconstitute(
                        overlay.id(),
                        policy,
                        overlay.version(),
                        overlay.parentOverlayHash(),
                        overlay.allowedPaths(),
                        overlay.commandCatalog(),
                        overlay.sandboxBudget(),
                        overlay.operationBudget(),
                        TaskFactHash.sha256("tampered"),
                        overlay.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> WorkspacePolicyOverlay.reconstitute(
                        overlay.id(),
                        policy,
                        1,
                        Optional.empty(),
                        AllowedPathSet.of("crewscope-domain/src"),
                        overlay.commandCatalog(),
                        overlay.sandboxBudget(),
                        overlay.operationBudget(),
                        overlay.overlayHash(),
                        overlay.audit()));
    }

    private static WorkspacePolicy reconstitute(WorkspacePolicy policy, TaskFactHash expectedHash) {
        return WorkspacePolicy.reconstitute(
                policy.id(),
                policy.scope(),
                policy.taskId(),
                policy.taskExecutionId(),
                policy.attempt(),
                policy.codingTarget(),
                policy.policySnapshotId(),
                policy.policySnapshotHash(),
                policy.allowedPaths(),
                policy.buildProfile(),
                policy.commandCatalog(),
                policy.sandboxBudget(),
                policy.operationBudget(),
                expectedHash,
                policy.createdByPrincipalId(),
                policy.createdAt());
    }

    private record Fixture(
            TaskDomainFixture domain,
            Task task,
            CodingTargetSnapshot target,
            TaskExecution rawExecution,
            TaskExecution execution,
            PolicySnapshot policy,
            BuildProfile profile,
            AllowedPathSet paths,
            SandboxResourceBudget sandbox,
            WorkspaceOperationBudget operations) {

        static Fixture create() {
            TaskDomainFixture domain = new TaskDomainFixture();
            Task task = Task.create(
                    TaskId.generate(),
                    domain.workItem,
                    TaskSource.fromWorkItem(domain.workItem),
                    new TaskBrief(
                            "Implement WorkspacePolicy",
                            List.of("Commands are controlled", "Budgets are enforced")),
                    domain.snapshot(),
                    domain.owner,
                    TaskDomainFixture.CREATED_AT);
            BuildCommand test = new BuildCommand(
                    "command.mavenTest", List.of("./mvnw", "test"), ".", 60, 900);
            BuildCommand verify = new BuildCommand(
                    "command.mavenVerify", List.of("./mvnw", "verify"), ".", 60, 900);
            BuildProfile profile = BuildProfile.define(
                    "maven-java-17",
                    1,
                    BuildTool.MAVEN_WRAPPER,
                    17,
                    new SandboxImageReference(
                            "eclipse-temurin:17-jdk@sha256:" + "a".repeat(64)),
                    new CommandCatalog(Map.of(
                            CommandKind.TEST, test,
                            CommandKind.VERIFY, verify)));
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
            PolicySnapshot policy = policy(
                    task,
                    rawExecution,
                    domain,
                    Set.of(ExecutionCapability.SANDBOX, ExecutionCapability.WORKTREE),
                    Set.of("command.mavenTest", "command.mavenVerify"),
                    new PolicyBudget(120_000, 40, 160, 900));
            SafetyEnforcementOverlay safety = SafetyEnforcementOverlay.unrestricted(
                    SafetyEnforcementOverlayId.generate(),
                    task,
                    rawExecution,
                    domain.owner,
                    POLICY_AT);
            TaskExecution execution = rawExecution.initializePlanningContext(
                    policy, safety, 0, domain.owner, POLICY_AT);
            return new Fixture(
                    domain,
                    task,
                    target,
                    rawExecution,
                    execution,
                    policy,
                    profile,
                    AllowedPathSet.of("crewscope-domain", "docs"),
                    new SandboxResourceBudget(
                            SandboxNetworkMode.NONE, 2, 2_048, 256, 900, 1_048_576, true),
                    new WorkspaceOperationBudget(
                            12, 20, 262_144, 80, 1_048_576, 524_288, 3));
        }

        PolicySnapshot policy(
                Set<ExecutionCapability> capabilities,
                Set<String> tools,
                PolicyBudget budget) {
            return policy(task, rawExecution, domain, capabilities, tools, budget);
        }

        WorkspacePolicy workspacePolicy() {
            return workspacePolicy(paths, policy);
        }

        WorkspacePolicy workspacePolicy(AllowedPathSet targetPaths, PolicySnapshot targetPolicy) {
            return workspacePolicy(targetPaths, targetPolicy, sandbox, operations);
        }

        WorkspacePolicy workspacePolicy(
                AllowedPathSet targetPaths,
                PolicySnapshot targetPolicy,
                SandboxResourceBudget targetSandbox,
                WorkspaceOperationBudget targetOperations) {
            return WorkspacePolicy.create(
                    WorkspacePolicyId.generate(),
                    target,
                    execution,
                    targetPolicy,
                    profile,
                    targetPaths,
                    targetSandbox,
                    targetOperations,
                    domain.owner,
                    POLICY_AT);
        }

        private static PolicySnapshot policy(
                Task task,
                TaskExecution execution,
                TaskDomainFixture domain,
                Set<ExecutionCapability> capabilities,
                Set<String> tools,
                PolicyBudget budget) {
            return PolicySnapshot.initial(
                    PolicySnapshotId.generate(),
                    task,
                    execution,
                    domain.executor,
                    new PolicyPackReference(PolicyPackId.generate(), 1),
                    AgentProfileId.generate(),
                    1,
                    capabilities,
                    tools,
                    Set.of(),
                    budget,
                    domain.owner,
                    POLICY_AT);
        }
    }
}
