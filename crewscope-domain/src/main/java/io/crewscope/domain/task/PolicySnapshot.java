package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable authorization and runtime configuration snapshot for one TaskExecution. */
public final class PolicySnapshot {

    private static final int MAX_ITEMS = 200;
    private static final String KEY_PATTERN = "[a-z][A-Za-z0-9._-]{0,127}";

    private final PolicySnapshotId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId executionId;
    private final long revision;
    private final Optional<PolicySnapshotId> parentSnapshotId;
    private final PolicySnapshotChangeReason changeReason;
    private final ExecutionPrincipalSnapshot executionPrincipal;
    private final PolicyPackReference policyPack;
    private final AgentProfileId agentProfileId;
    private final long agentProfileVersion;
    private final Set<ExecutionCapability> capabilities;
    private final Set<String> allowedTools;
    private final Set<ProviderBindingId> providerBindingIds;
    private final PolicyBudget budget;
    private final TaskFactHash snapshotHash;
    private final PrincipalId createdByPrincipalId;
    private final UtcTimestamp createdAt;

    private PolicySnapshot(
            PolicySnapshotId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long revision,
            Optional<PolicySnapshotId> parentSnapshotId,
            PolicySnapshotChangeReason changeReason,
            ExecutionPrincipalSnapshot executionPrincipal,
            PolicyPackReference policyPack,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Set<ExecutionCapability> capabilities,
            Set<String> allowedTools,
            Set<ProviderBindingId> providerBindingIds,
            PolicyBudget budget,
            TaskFactHash snapshotHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.revision = requireRevision(revision);
        this.parentSnapshotId = requireParent(id, revision, parentSnapshotId);
        this.changeReason = requireChangeReason(revision, changeReason);
        this.executionPrincipal = Objects.requireNonNull(executionPrincipal, "executionPrincipal");
        this.policyPack = Objects.requireNonNull(policyPack, "policyPack");
        this.agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new DomainValidationException(
                    "policySnapshot.agentProfileVersion", "must not be negative");
        }
        this.agentProfileVersion = agentProfileVersion;
        this.capabilities = requireCapabilities(capabilities);
        this.allowedTools = requireKeys(allowedTools, "policySnapshot.allowedTools", false);
        this.providerBindingIds = requireBindings(providerBindingIds);
        this.budget = Objects.requireNonNull(budget, "budget");
        this.createdByPrincipalId = Objects.requireNonNull(
                createdByPrincipalId, "createdByPrincipalId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        TaskFactHash expectedHash = calculateHash();
        if (!expectedHash.equals(Objects.requireNonNull(snapshotHash, "snapshotHash"))) {
            throw new DomainValidationException(
                    "policySnapshot.snapshotHash", "must match the canonical snapshot facts");
        }
        this.snapshotHash = expectedHash;
    }

    /** Creates revision one from closed Task, execution, responsibility and configuration facts. */
    public static PolicySnapshot initial(
            PolicySnapshotId id,
            Task task,
            TaskExecution execution,
            Principal executor,
            PolicyPackReference policyPack,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Set<ExecutionCapability> capabilities,
            Set<String> allowedTools,
            Set<ProviderBindingId> providerBindingIds,
            PolicyBudget budget,
            Principal actor,
            UtcTimestamp createdAt) {
        Task requiredTask = requireTaskExecution(task, execution);
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredTask.scope(), "policySnapshot.createdByPrincipalId");
        ExecutionPrincipalSnapshot executionPrincipal = ExecutionPrincipalSnapshot.resolve(
                requiredTask.responsibilitySnapshot(), executor);
        return build(
                id,
                requiredTask.scope(),
                requiredTask.id(),
                execution.id(),
                1,
                Optional.empty(),
                PolicySnapshotChangeReason.TASK_CREATED,
                executionPrincipal,
                policyPack,
                agentProfileId,
                agentProfileVersion,
                capabilities,
                allowedTools,
                providerBindingIds,
                budget,
                actorId,
                createdAt);
    }

    /** Creates a new immutable revision; the parent remains unchanged and auditable. */
    public static PolicySnapshot supersede(
            PolicySnapshotId id,
            PolicySnapshot parent,
            PolicySnapshotChangeReason reason,
            ExecutionPrincipalSnapshot executionPrincipal,
            PolicyPackReference policyPack,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Set<ExecutionCapability> capabilities,
            Set<String> allowedTools,
            Set<ProviderBindingId> providerBindingIds,
            PolicyBudget budget,
            Principal actor,
            UtcTimestamp createdAt) {
        PolicySnapshot requiredParent = Objects.requireNonNull(parent, "parent");
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredParent.scope, "policySnapshot.createdByPrincipalId");
        PolicySnapshotChangeReason requiredReason = Objects.requireNonNull(reason, "reason");
        if (requiredReason == PolicySnapshotChangeReason.TASK_CREATED) {
            throw new DomainValidationException(
                    "policySnapshot.changeReason", "TASK_CREATED is only valid for revision one");
        }
        PolicySnapshot replacement = build(
                id,
                requiredParent.scope,
                requiredParent.taskId,
                requiredParent.executionId,
                requiredParent.revision + 1,
                Optional.of(requiredParent.id),
                requiredReason,
                executionPrincipal,
                policyPack,
                agentProfileId,
                agentProfileVersion,
                capabilities,
                allowedTools,
                providerBindingIds,
                budget,
                actorId,
                createdAt);
        if (replacement.sameEffectivePolicy(requiredParent)) {
            throw new DomainValidationException(
                    "policySnapshot", "must differ from the parent effective policy");
        }
        return replacement;
    }

    /** Reconstitutes an immutable snapshot and verifies its canonical hash. */
    public static PolicySnapshot reconstitute(
            PolicySnapshotId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long revision,
            Optional<PolicySnapshotId> parentSnapshotId,
            PolicySnapshotChangeReason changeReason,
            ExecutionPrincipalSnapshot executionPrincipal,
            PolicyPackReference policyPack,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Set<ExecutionCapability> capabilities,
            Set<String> allowedTools,
            Set<ProviderBindingId> providerBindingIds,
            PolicyBudget budget,
            TaskFactHash snapshotHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt) {
        return new PolicySnapshot(
                id,
                scope,
                taskId,
                executionId,
                revision,
                parentSnapshotId,
                changeReason,
                executionPrincipal,
                policyPack,
                agentProfileId,
                agentProfileVersion,
                capabilities,
                allowedTools,
                providerBindingIds,
                budget,
                snapshotHash,
                createdByPrincipalId,
                createdAt);
    }

    public boolean allows(Set<ExecutionCapability> requiredCapabilities, Set<String> requiredTools) {
        return capabilities.containsAll(Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"))
                && allowedTools.containsAll(requireKeys(
                        requiredTools, "planVersion.requiredTools", true));
    }

    public boolean expands(PolicySnapshot parent) {
        PolicySnapshot required = requireSameLineage(parent);
        return !required.capabilities.containsAll(capabilities)
                || !required.allowedTools.containsAll(allowedTools)
                || !required.providerBindingIds.containsAll(providerBindingIds)
                || budget.maxTokens() > required.budget.maxTokens()
                || budget.maxModelCalls() > required.budget.maxModelCalls()
                || budget.maxToolCalls() > required.budget.maxToolCalls()
                || budget.maxDurationSeconds() > required.budget.maxDurationSeconds();
    }

    private PolicySnapshot requireSameLineage(PolicySnapshot parent) {
        PolicySnapshot required = Objects.requireNonNull(parent, "parent");
        if (!scope.equals(required.scope)
                || !taskId.equals(required.taskId)
                || !executionId.equals(required.executionId)
                || parentSnapshotId.filter(required.id::equals).isEmpty()) {
            throw new DomainValidationException(
                    "policySnapshot.parentSnapshotId", "must identify the immediate parent lineage");
        }
        return required;
    }

    private boolean sameEffectivePolicy(PolicySnapshot other) {
        return executionPrincipal.equals(other.executionPrincipal)
                && policyPack.equals(other.policyPack)
                && agentProfileId.equals(other.agentProfileId)
                && agentProfileVersion == other.agentProfileVersion
                && capabilities.equals(other.capabilities)
                && allowedTools.equals(other.allowedTools)
                && providerBindingIds.equals(other.providerBindingIds)
                && budget.equals(other.budget);
    }

    private static PolicySnapshot build(
            PolicySnapshotId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long revision,
            Optional<PolicySnapshotId> parentSnapshotId,
            PolicySnapshotChangeReason changeReason,
            ExecutionPrincipalSnapshot executionPrincipal,
            PolicyPackReference policyPack,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Set<ExecutionCapability> capabilities,
            Set<String> allowedTools,
            Set<ProviderBindingId> providerBindingIds,
            PolicyBudget budget,
            PrincipalId actorId,
            UtcTimestamp createdAt) {
        PolicySnapshot unhashed = new PolicySnapshot(
                id,
                scope,
                taskId,
                executionId,
                revision,
                parentSnapshotId,
                changeReason,
                executionPrincipal,
                policyPack,
                agentProfileId,
                agentProfileVersion,
                capabilities,
                allowedTools,
                providerBindingIds,
                budget,
                TaskFactHash.sha256("placeholder"),
                actorId,
                createdAt,
                true);
        return new PolicySnapshot(
                id,
                scope,
                taskId,
                executionId,
                revision,
                parentSnapshotId,
                changeReason,
                executionPrincipal,
                policyPack,
                agentProfileId,
                agentProfileVersion,
                capabilities,
                allowedTools,
                providerBindingIds,
                budget,
                unhashed.calculateHash(),
                actorId,
                createdAt);
    }

    private PolicySnapshot(
            PolicySnapshotId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long revision,
            Optional<PolicySnapshotId> parentSnapshotId,
            PolicySnapshotChangeReason changeReason,
            ExecutionPrincipalSnapshot executionPrincipal,
            PolicyPackReference policyPack,
            AgentProfileId agentProfileId,
            long agentProfileVersion,
            Set<ExecutionCapability> capabilities,
            Set<String> allowedTools,
            Set<ProviderBindingId> providerBindingIds,
            PolicyBudget budget,
            TaskFactHash ignoredHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt,
            boolean skipHashValidation) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.revision = requireRevision(revision);
        this.parentSnapshotId = requireParent(id, revision, parentSnapshotId);
        this.changeReason = requireChangeReason(revision, changeReason);
        this.executionPrincipal = Objects.requireNonNull(executionPrincipal, "executionPrincipal");
        this.policyPack = Objects.requireNonNull(policyPack, "policyPack");
        this.agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        if (agentProfileVersion < 0) {
            throw new DomainValidationException(
                    "policySnapshot.agentProfileVersion", "must not be negative");
        }
        this.agentProfileVersion = agentProfileVersion;
        this.capabilities = requireCapabilities(capabilities);
        this.allowedTools = requireKeys(allowedTools, "policySnapshot.allowedTools", false);
        this.providerBindingIds = requireBindings(providerBindingIds);
        this.budget = Objects.requireNonNull(budget, "budget");
        this.snapshotHash = Objects.requireNonNull(ignoredHash, "ignoredHash");
        this.createdByPrincipalId = Objects.requireNonNull(createdByPrincipalId, "createdByPrincipalId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private TaskFactHash calculateHash() {
        return TaskFactHash.sha256(String.join("|",
                id.toString(), scope.organizationId().toString(), scope.teamId().toString(),
                scope.workspaceId().toString(), scope.projectId().toString(), taskId.toString(),
                executionId.toString(), Long.toString(revision),
                parentSnapshotId.map(Object::toString).orElse("-"), changeReason.name(),
                executionPrincipal.principalId().toString(), executionPrincipal.assignmentId().toString(),
                Long.toString(executionPrincipal.assignmentVersion()),
                executionPrincipal.responsibilitySnapshotHash().toString(), policyPack.id().toString(),
                Long.toString(policyPack.version()), agentProfileId.toString(),
                Long.toString(agentProfileVersion), sorted(capabilities), sorted(allowedTools),
                sorted(providerBindingIds), Long.toString(budget.maxTokens()),
                Integer.toString(budget.maxModelCalls()), Integer.toString(budget.maxToolCalls()),
                Long.toString(budget.maxDurationSeconds()), createdByPrincipalId.toString(),
                createdAt.toString()));
    }

    private static Task requireTaskExecution(Task task, TaskExecution execution) {
        Task requiredTask = Objects.requireNonNull(task, "task");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        if (!requiredTask.scope().equals(requiredExecution.scope())
                || !requiredTask.id().equals(requiredExecution.taskId())) {
            throw new DomainValidationException(
                    "policySnapshot.executionId", "must belong to the Task and complete scope");
        }
        return requiredTask;
    }

    private static long requireRevision(long value) {
        if (value < 1) {
            throw new DomainValidationException("policySnapshot.revision", "must be positive");
        }
        return value;
    }

    private static Optional<PolicySnapshotId> requireParent(
            PolicySnapshotId id, long revision, Optional<PolicySnapshotId> parent) {
        Optional<PolicySnapshotId> required = Objects.requireNonNull(parent, "parentSnapshotId");
        if ((revision == 1) == required.isPresent()) {
            throw new DomainValidationException(
                    "policySnapshot.parentSnapshotId",
                    revision == 1 ? "must be empty for revision one" : "is required after revision one");
        }
        if (required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "policySnapshot.parentSnapshotId", "must not reference itself");
        }
        return required;
    }

    private static PolicySnapshotChangeReason requireChangeReason(
            long revision, PolicySnapshotChangeReason reason) {
        PolicySnapshotChangeReason required = Objects.requireNonNull(reason, "changeReason");
        if ((revision == 1) != (required == PolicySnapshotChangeReason.TASK_CREATED)) {
            throw new DomainValidationException(
                    "policySnapshot.changeReason", "must match initial or superseding revision");
        }
        return required;
    }

    private static Set<ExecutionCapability> requireCapabilities(Set<ExecutionCapability> values) {
        Set<ExecutionCapability> required = Set.copyOf(Objects.requireNonNull(values, "capabilities"));
        if (required.isEmpty() || required.size() > MAX_ITEMS) {
            throw new DomainValidationException(
                    "policySnapshot.capabilities", "must contain 1 to 200 values");
        }
        return required;
    }

    static Set<String> requireKeys(Set<String> values, String field, boolean emptyAllowed) {
        Set<String> required = Objects.requireNonNull(values, "values").stream()
                .map(value -> Objects.requireNonNull(value, "value").strip())
                .collect(Collectors.toUnmodifiableSet());
        if ((!emptyAllowed && required.isEmpty()) || required.size() > MAX_ITEMS
                || required.stream().anyMatch(value -> !value.matches(KEY_PATTERN))) {
            throw new DomainValidationException(field, "contains invalid or unsupported keys");
        }
        return required;
    }

    private static Set<ProviderBindingId> requireBindings(Set<ProviderBindingId> values) {
        Set<ProviderBindingId> required = Set.copyOf(Objects.requireNonNull(values, "providerBindingIds"));
        if (required.size() > MAX_ITEMS) {
            throw new DomainValidationException(
                    "policySnapshot.providerBindingIds", "must not exceed 200 values");
        }
        return required;
    }

    private static String sorted(Collection<?> values) {
        return values.stream().map(Object::toString).sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
    }

    public PolicySnapshotId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId executionId() { return executionId; }
    public long revision() { return revision; }
    public Optional<PolicySnapshotId> parentSnapshotId() { return parentSnapshotId; }
    public PolicySnapshotChangeReason changeReason() { return changeReason; }
    public ExecutionPrincipalSnapshot executionPrincipal() { return executionPrincipal; }
    public PolicyPackReference policyPack() { return policyPack; }
    public AgentProfileId agentProfileId() { return agentProfileId; }
    public long agentProfileVersion() { return agentProfileVersion; }
    public Set<ExecutionCapability> capabilities() { return capabilities; }
    public Set<String> allowedTools() { return allowedTools; }
    public Set<ProviderBindingId> providerBindingIds() { return providerBindingIds; }
    public PolicyBudget budget() { return budget; }
    public TaskFactHash snapshotHash() { return snapshotHash; }
    public PrincipalId createdByPrincipalId() { return createdByPrincipalId; }
    public UtcTimestamp createdAt() { return createdAt; }
}
