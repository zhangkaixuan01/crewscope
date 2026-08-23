package io.crewscope.domain.task;

import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable version in a monotonic safety restriction stream for one TaskExecution. */
public final class SafetyEnforcementOverlay {

    private final SafetyEnforcementOverlayId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId executionId;
    private final long version;
    private final Optional<TaskFactHash> parentOverlayHash;
    private final Set<SafetyRestriction> restrictions;
    private final Set<ExecutionCapability> disabledCapabilities;
    private final Set<String> disabledTools;
    private final TaskFactHash overlayHash;
    private final PrincipalId createdByPrincipalId;
    private final UtcTimestamp createdAt;

    private SafetyEnforcementOverlay(
            SafetyEnforcementOverlayId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long version,
            Optional<TaskFactHash> parentOverlayHash,
            Set<SafetyRestriction> restrictions,
            Set<ExecutionCapability> disabledCapabilities,
            Set<String> disabledTools,
            TaskFactHash overlayHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt,
            boolean validateHash) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        if (version < 1) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay.version", "must be positive");
        }
        this.version = version;
        this.parentOverlayHash = requireParent(version, parentOverlayHash);
        this.restrictions = Set.copyOf(Objects.requireNonNull(restrictions, "restrictions"));
        this.disabledCapabilities = Set.copyOf(
                Objects.requireNonNull(disabledCapabilities, "disabledCapabilities"));
        this.disabledTools = PolicySnapshot.requireKeys(
                disabledTools, "safetyEnforcementOverlay.disabledTools", true);
        this.createdByPrincipalId = Objects.requireNonNull(
                createdByPrincipalId, "createdByPrincipalId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        TaskFactHash expected = calculateHash();
        if (validateHash && !expected.equals(Objects.requireNonNull(overlayHash, "overlayHash"))) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay.overlayHash", "must match canonical restrictions");
        }
        this.overlayHash = expected;
    }

    /** Creates an initially unrestricted overlay stream. */
    public static SafetyEnforcementOverlay unrestricted(
            SafetyEnforcementOverlayId id,
            Task task,
            TaskExecution execution,
            Principal actor,
            UtcTimestamp createdAt) {
        requireTaskExecution(task, execution);
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, task.scope(), "safetyEnforcementOverlay.createdByPrincipalId");
        return build(
                id,
                task.scope(),
                task.id(),
                execution.id(),
                1,
                Optional.empty(),
                Set.of(),
                Set.of(),
                Set.of(),
                actorId,
                createdAt);
    }

    /** Adds restrictions; removal is forbidden so a newer version can never expand access. */
    public SafetyEnforcementOverlay tighten(
            Set<SafetyRestriction> addedRestrictions,
            Set<ExecutionCapability> additionallyDisabledCapabilities,
            Set<String> additionallyDisabledTools,
            Principal actor,
            UtcTimestamp createdAt) {
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope, "safetyEnforcementOverlay.createdByPrincipalId");
        Set<SafetyRestriction> nextRestrictions = union(restrictions, addedRestrictions);
        Set<ExecutionCapability> nextCapabilities = union(
                disabledCapabilities, additionallyDisabledCapabilities);
        Set<String> nextTools = union(
                disabledTools,
                PolicySnapshot.requireKeys(
                        additionallyDisabledTools,
                        "safetyEnforcementOverlay.disabledTools",
                        true));
        if (nextRestrictions.equals(restrictions)
                && nextCapabilities.equals(disabledCapabilities)
                && nextTools.equals(disabledTools)) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay", "must add at least one restriction");
        }
        return build(
                id,
                scope,
                taskId,
                executionId,
                version + 1,
                Optional.of(overlayHash),
                nextRestrictions,
                nextCapabilities,
                nextTools,
                actorId,
                createdAt);
    }

    public boolean permits(
            PolicySnapshot snapshot,
            Set<ExecutionCapability> requiredCapabilities,
            Set<String> requiredTools) {
        PolicySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (!scope.equals(requiredSnapshot.scope())
                || !taskId.equals(requiredSnapshot.taskId())
                || !executionId.equals(requiredSnapshot.executionId())) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay", "must share PolicySnapshot lineage and scope");
        }
        Set<ExecutionCapability> capabilities = Set.copyOf(
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        Set<String> tools = PolicySnapshot.requireKeys(
                requiredTools, "planVersion.requiredTools", true);
        return requiredSnapshot.allows(capabilities, tools)
                && java.util.Collections.disjoint(disabledCapabilities, capabilities)
                && java.util.Collections.disjoint(disabledTools, tools)
                && !restrictions.contains(SafetyRestriction.PRINCIPAL_DISABLED)
                && !restrictions.contains(SafetyRestriction.MEMBERSHIP_DISABLED);
    }

    /**
     * Applies current kill switches to an already fixed Schema v2 model. The overlay never selects
     * another model, changes coordinates or grants a role absent from the PolicySnapshot.
     */
    public boolean permitsModelInvocation(PolicySnapshot snapshot, ResolvedModelRole role) {
        PolicySnapshot requiredSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        ResolvedModelRole requiredRole = Objects.requireNonNull(role, "role");
        if (!scope.equals(requiredSnapshot.scope())
                || !taskId.equals(requiredSnapshot.taskId())
                || !executionId.equals(requiredSnapshot.executionId())) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay", "must share PolicySnapshot lineage and scope");
        }
        boolean roleWasFixed = requiredSnapshot.agentExecutionConfiguration()
                .map(configuration -> requiredRole == ResolvedModelRole.PRIMARY
                        || configuration.fallback().isPresent())
                .orElse(false);
        return roleWasFixed
                && !restrictions.contains(SafetyRestriction.PRINCIPAL_DISABLED)
                && !restrictions.contains(SafetyRestriction.MEMBERSHIP_DISABLED)
                && !restrictions.contains(SafetyRestriction.MODEL_DISABLED)
                && !restrictions.contains(SafetyRestriction.CONNECTION_REVOKED)
                && !restrictions.contains(SafetyRestriction.CREDENTIAL_REVOKED);
    }

    public static SafetyEnforcementOverlay reconstitute(
            SafetyEnforcementOverlayId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long version,
            Optional<TaskFactHash> parentOverlayHash,
            Set<SafetyRestriction> restrictions,
            Set<ExecutionCapability> disabledCapabilities,
            Set<String> disabledTools,
            TaskFactHash overlayHash,
            PrincipalId createdByPrincipalId,
            UtcTimestamp createdAt) {
        return new SafetyEnforcementOverlay(
                id,
                scope,
                taskId,
                executionId,
                version,
                parentOverlayHash,
                restrictions,
                disabledCapabilities,
                disabledTools,
                overlayHash,
                createdByPrincipalId,
                createdAt,
                true);
    }

    private static SafetyEnforcementOverlay build(
            SafetyEnforcementOverlayId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long version,
            Optional<TaskFactHash> parentOverlayHash,
            Set<SafetyRestriction> restrictions,
            Set<ExecutionCapability> disabledCapabilities,
            Set<String> disabledTools,
            PrincipalId actorId,
            UtcTimestamp createdAt) {
        return new SafetyEnforcementOverlay(
                id,
                scope,
                taskId,
                executionId,
                version,
                parentOverlayHash,
                restrictions,
                disabledCapabilities,
                disabledTools,
                TaskFactHash.sha256("placeholder"),
                actorId,
                createdAt,
                false);
    }

    private TaskFactHash calculateHash() {
        return TaskFactHash.sha256(String.join("|",
                id.toString(), scope.organizationId().toString(), scope.teamId().toString(),
                scope.workspaceId().toString(), scope.projectId().toString(), taskId.toString(),
                executionId.toString(), Long.toString(version),
                parentOverlayHash.map(Object::toString).orElse("-"), sorted(restrictions),
                sorted(disabledCapabilities), sorted(disabledTools), createdByPrincipalId.toString(),
                createdAt.toString()));
    }

    private static Optional<TaskFactHash> requireParent(
            long version, Optional<TaskFactHash> parentOverlayHash) {
        Optional<TaskFactHash> required = Objects.requireNonNull(
                parentOverlayHash, "parentOverlayHash");
        if ((version == 1) == required.isPresent()) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay.parentOverlayHash",
                    version == 1 ? "must be empty for version one" : "is required after version one");
        }
        return required;
    }

    private static void requireTaskExecution(Task task, TaskExecution execution) {
        Task requiredTask = Objects.requireNonNull(task, "task");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        if (!requiredTask.id().equals(requiredExecution.taskId())
                || !requiredTask.scope().equals(requiredExecution.scope())) {
            throw new DomainValidationException(
                    "safetyEnforcementOverlay.executionId", "must belong to the Task and scope");
        }
    }

    private static <T> Set<T> union(Set<T> current, Set<T> added) {
        java.util.HashSet<T> union = new java.util.HashSet<>(current);
        union.addAll(Set.copyOf(Objects.requireNonNull(added, "added")));
        return Set.copyOf(union);
    }

    private static String sorted(Set<?> values) {
        return values.stream().map(Object::toString).sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(","));
    }

    public SafetyEnforcementOverlayReference reference() {
        return new SafetyEnforcementOverlayReference(id, version, overlayHash);
    }

    public SafetyEnforcementOverlayId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId executionId() { return executionId; }
    public long version() { return version; }
    public Optional<TaskFactHash> parentOverlayHash() { return parentOverlayHash; }
    public Set<SafetyRestriction> restrictions() { return restrictions; }
    public Set<ExecutionCapability> disabledCapabilities() { return disabledCapabilities; }
    public Set<String> disabledTools() { return disabledTools; }
    public TaskFactHash overlayHash() { return overlayHash; }
    public PrincipalId createdByPrincipalId() { return createdByPrincipalId; }
    public UtcTimestamp createdAt() { return createdAt; }
}
