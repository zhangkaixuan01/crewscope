package io.crewscope.domain.coding;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable effective Coding workspace authority for one TaskExecution attempt. */
public final class WorkspacePolicy {

    private static final Set<ExecutionCapability> REQUIRED_CAPABILITIES =
            Set.of(ExecutionCapability.SANDBOX, ExecutionCapability.WORKTREE);

    private final WorkspacePolicyId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final CodingTargetSnapshotReference codingTarget;
    private final PolicySnapshotId policySnapshotId;
    private final TaskFactHash policySnapshotHash;
    private final AllowedPathSet allowedPaths;
    private final BuildProfileReference buildProfile;
    private final CommandCatalog commandCatalog;
    private final SandboxResourceBudget sandboxBudget;
    private final WorkspaceOperationBudget operationBudget;
    private final TaskFactHash policyHash;
    private final PrincipalId createdByPrincipalId;
    private final UtcTimestamp createdAt;

    private WorkspacePolicy(
            WorkspacePolicyId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            CodingTargetSnapshotReference codingTarget,
            PolicySnapshotId policySnapshotId,
            TaskFactHash policySnapshotHash,
            AllowedPathSet allowedPaths,
            BuildProfileReference buildProfile,
            CommandCatalog commandCatalog,
            SandboxResourceBudget sandboxBudget,
            WorkspaceOperationBudget operationBudget,
            Optional<TaskFactHash> expectedHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1) {
            throw new DomainValidationException("workspacePolicy.attempt", "must be positive");
        }
        this.attempt = attempt;
        this.codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        this.policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        this.policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
        this.allowedPaths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        this.buildProfile = Objects.requireNonNull(buildProfile, "buildProfile");
        this.commandCatalog = Objects.requireNonNull(commandCatalog, "commandCatalog");
        this.sandboxBudget = Objects.requireNonNull(sandboxBudget, "sandboxBudget");
        this.operationBudget = Objects.requireNonNull(operationBudget, "operationBudget");
        requirePersistedShape(this.commandCatalog, this.sandboxBudget);
        this.createdByPrincipalId = Objects.requireNonNull(
                createdByPrincipalId, "createdByPrincipalId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.policyHash = calculateHash();
        Objects.requireNonNull(expectedHash, "expectedHash").ifPresent(expected -> {
            if (!expected.equals(this.policyHash)) {
                throw new DomainValidationException(
                        "workspacePolicy.policyHash", "must match the canonical policy facts");
            }
        });
    }

    /** Closes Coding target, execution, PolicySnapshot and exact BuildProfile facts. */
    public static WorkspacePolicy create(
            WorkspacePolicyId id,
            CodingTargetSnapshot codingTarget,
            TaskExecution execution,
            PolicySnapshot policySnapshot,
            BuildProfile buildProfile,
            AllowedPathSet allowedPaths,
            SandboxResourceBudget sandboxBudget,
            WorkspaceOperationBudget operationBudget,
            Principal actor,
            UtcTimestamp createdAt) {
        CodingTargetSnapshot target = Objects.requireNonNull(codingTarget, "codingTarget");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        PolicySnapshot policy = Objects.requireNonNull(policySnapshot, "policySnapshot");
        BuildProfile profile = Objects.requireNonNull(buildProfile, "buildProfile");
        requireLineage(target, requiredExecution, policy);
        requirePlanningContext(requiredExecution, policy);
        if (!target.buildProfile().equals(profile.reference())) {
            throw new DomainValidationException(
                    "workspacePolicy.buildProfile", "must resolve the exact CodingTarget reference");
        }
        AllowedPathSet paths = Objects.requireNonNull(allowedPaths, "allowedPaths");
        if (!AllowedPathSet.from(target.allowedPaths()).containsAll(paths)) {
            throw new DomainValidationException(
                    "workspacePolicy.allowedPaths", "must be within CodingTarget allowed paths");
        }
        CommandCatalog catalog = profile.commandCatalog();
        if (!policy.capabilities().containsAll(REQUIRED_CAPABILITIES)
                || !policy.allowedTools().containsAll(catalog.toolKeys())) {
            throw new DomainValidationException(
                    "workspacePolicy.policySnapshot",
                    "must authorize Sandbox, Worktree and every command Tool");
        }
        requireBudgets(policy, catalog, sandboxBudget, operationBudget);
        PrincipalId actorId = CodingTargetActorPolicy.requireActiveInScope(
                actor, target.scope(), "workspacePolicy.createdByPrincipalId");
        return new WorkspacePolicy(
                id,
                target.scope(),
                target.taskId(),
                requiredExecution.id(),
                requiredExecution.attempt(),
                target.reference(),
                policy.id(),
                policy.snapshotHash(),
                paths,
                profile.reference(),
                catalog,
                sandboxBudget,
                operationBudget,
                Optional.empty(),
                actorId,
                createdAt);
    }

    /** Reconstitutes persisted facts and verifies the canonical policy hash. */
    public static WorkspacePolicy reconstitute(
            WorkspacePolicyId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId taskExecutionId,
            int attempt,
            CodingTargetSnapshotReference codingTarget,
            PolicySnapshotId policySnapshotId,
            TaskFactHash policySnapshotHash,
            AllowedPathSet allowedPaths,
            BuildProfileReference buildProfile,
            CommandCatalog commandCatalog,
            SandboxResourceBudget sandboxBudget,
            WorkspaceOperationBudget operationBudget,
            TaskFactHash policyHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt) {
        return new WorkspacePolicy(
                id,
                scope,
                taskId,
                taskExecutionId,
                attempt,
                codingTarget,
                policySnapshotId,
                policySnapshotHash,
                allowedPaths,
                buildProfile,
                commandCatalog,
                sandboxBudget,
                operationBudget,
                Optional.of(Objects.requireNonNull(policyHash, "policyHash")),
                createdByPrincipalId,
                createdAt);
    }

    private static void requireLineage(
            CodingTargetSnapshot target, TaskExecution execution, PolicySnapshot policy) {
        if (!target.scope().equals(execution.scope())
                || !target.scope().equals(policy.scope())
                || !target.taskId().equals(execution.taskId())
                || !target.taskId().equals(policy.taskId())
                || !execution.id().equals(policy.executionId())) {
            throw new DomainValidationException(
                    "workspacePolicy.taskExecutionId",
                    "CodingTarget, execution and PolicySnapshot must share complete scope and lineage");
        }
    }

    private static void requirePlanningContext(TaskExecution execution, PolicySnapshot policy) {
        TaskExecutionPlanningContext context = execution.planningContext().orElseThrow(() ->
                new DomainValidationException(
                        "workspacePolicy.policySnapshotId",
                        "TaskExecution planning context must be initialized"));
        if (!context.policySnapshotId().equals(policy.id())
                || !context.policySnapshotHash().equals(policy.snapshotHash())) {
            throw new DomainValidationException(
                    "workspacePolicy.policySnapshotId",
                    "must identify the TaskExecution current PolicySnapshot ID and hash");
        }
    }

    private static void requireBudgets(
            PolicySnapshot policy,
            CommandCatalog catalog,
            SandboxResourceBudget sandbox,
            WorkspaceOperationBudget operations) {
        SandboxResourceBudget requiredSandbox = Objects.requireNonNull(sandbox, "sandboxBudget");
        WorkspaceOperationBudget requiredOperations = Objects.requireNonNull(
                operations, "operationBudget");
        if (requiredSandbox.networkMode() != SandboxNetworkMode.NONE
                || !requiredSandbox.readOnlyRootFilesystem()) {
            throw new DomainValidationException(
                    "workspacePolicy.sandboxResourceBudget",
                    "M4 requires no network and a read-only root filesystem");
        }
        if (catalog.maximumCommandTimeoutSeconds() > requiredSandbox.maxCommandDurationSeconds()
                || requiredSandbox.maxCommandDurationSeconds() > policy.budget().maxDurationSeconds()) {
            throw new DomainValidationException(
                    "workspacePolicy.sandboxResourceBudget.maxCommandDurationSeconds",
                    "must cover catalog commands without exceeding PolicySnapshot duration");
        }
        long boundedToolCalls = (long) requiredOperations.maxCommandCalls()
                + requiredOperations.maxWriteOperations();
        if (boundedToolCalls > policy.budget().maxToolCalls()) {
            throw new DomainValidationException(
                    "workspacePolicy.operationBudget",
                    "command and write calls must fit the PolicySnapshot Tool budget");
        }
    }

    private static void requirePersistedShape(
            CommandCatalog catalog, SandboxResourceBudget sandbox) {
        if (catalog.commands().isEmpty()) {
            throw new DomainValidationException(
                    "workspacePolicy.commandCatalog", "must contain at least one command");
        }
        if (sandbox.networkMode() != SandboxNetworkMode.NONE
                || !sandbox.readOnlyRootFilesystem()
                || catalog.maximumCommandTimeoutSeconds() > sandbox.maxCommandDurationSeconds()) {
            throw new DomainValidationException(
                    "workspacePolicy.sandboxResourceBudget",
                    "must preserve no-network, read-only-root and command-timeout boundaries");
        }
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder("workspace-policy-v1");
        append(canonical, id.toString());
        append(canonical, scope.organizationId().toString());
        append(canonical, scope.teamId().toString());
        append(canonical, scope.workspaceId().toString());
        append(canonical, scope.projectId().toString());
        append(canonical, taskId.toString());
        append(canonical, taskExecutionId.toString());
        append(canonical, Integer.toString(attempt));
        append(canonical, codingTarget.snapshotId().toString());
        append(canonical, Long.toString(codingTarget.revision()));
        append(canonical, codingTarget.snapshotHash().toString());
        append(canonical, policySnapshotId.toString());
        append(canonical, policySnapshotHash.toString());
        allowedPaths.values().forEach(value -> append(canonical, value));
        append(canonical, buildProfile.key());
        append(canonical, Long.toString(buildProfile.version()));
        append(canonical, buildProfile.profileHash().toString());
        commandCatalog.commands().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    append(canonical, entry.getKey().name());
                    BuildCommand command = entry.getValue();
                    append(canonical, command.toolKey());
                    command.argv().forEach(value -> append(canonical, value));
                    append(canonical, command.workingDirectory());
                    append(canonical, Integer.toString(command.defaultTimeoutSeconds()));
                    append(canonical, Integer.toString(command.maxTimeoutSeconds()));
                    BuildProfile.appendSelectorPolicy(canonical, command.selectorPolicy());
                });
        append(canonical, sandboxBudget.networkMode().name());
        append(canonical, Integer.toString(sandboxBudget.cpuCount()));
        append(canonical, Integer.toString(sandboxBudget.memoryMiB()));
        append(canonical, Integer.toString(sandboxBudget.pids()));
        append(canonical, Integer.toString(sandboxBudget.maxCommandDurationSeconds()));
        append(canonical, Long.toString(sandboxBudget.maxCommandOutputBytes()));
        append(canonical, Boolean.toString(sandboxBudget.readOnlyRootFilesystem()));
        append(canonical, Integer.toString(operationBudget.maxCommandCalls()));
        append(canonical, Integer.toString(operationBudget.maxChangedFiles()));
        append(canonical, Long.toString(operationBudget.maxSingleFileBytes()));
        append(canonical, Integer.toString(operationBudget.maxWriteOperations()));
        append(canonical, Long.toString(operationBudget.maxWrittenBytes()));
        append(canonical, Long.toString(operationBudget.maxDiffBytes()));
        append(canonical, Integer.toString(operationBudget.maxTestRepairRounds()));
        append(canonical, createdByPrincipalId.toString());
        append(canonical, createdAt.toString());
        return TaskFactHash.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        BuildProfile.append(target, value);
    }

    public WorkspacePolicyReference reference() { return new WorkspacePolicyReference(id, policyHash); }

    public WorkspacePolicyId id() { return id; }

    public WorkItemScope scope() { return scope; }

    public TaskId taskId() { return taskId; }

    public TaskExecutionId taskExecutionId() { return taskExecutionId; }

    public int attempt() { return attempt; }

    public CodingTargetSnapshotReference codingTarget() { return codingTarget; }

    public PolicySnapshotId policySnapshotId() { return policySnapshotId; }

    public TaskFactHash policySnapshotHash() { return policySnapshotHash; }

    public AllowedPathSet allowedPaths() { return allowedPaths; }

    public BuildProfileReference buildProfile() { return buildProfile; }

    public CommandCatalog commandCatalog() { return commandCatalog; }

    public SandboxResourceBudget sandboxBudget() { return sandboxBudget; }

    public WorkspaceOperationBudget operationBudget() { return operationBudget; }

    public TaskFactHash policyHash() { return policyHash; }

    public PrincipalId createdByPrincipalId() { return createdByPrincipalId; }

    public UtcTimestamp createdAt() { return createdAt; }
}
