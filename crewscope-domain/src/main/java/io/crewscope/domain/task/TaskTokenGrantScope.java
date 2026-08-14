package io.crewscope.domain.task;

import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Complete immutable authorization coordinates shared by persisted grants and signed claims.
 *
 * <p>Keeping one value object on both sides prevents a signer or persistence mapper from silently
 * dropping a tenant, ownership, policy or resource boundary.
 */
public record TaskTokenGrantScope(
        WorkItemScope workItemScope,
        TaskId taskId,
        TaskExecutionId taskExecutionId,
        int attempt,
        ExecutionLeaseId executionLeaseId,
        RuntimeEnvironment environment,
        ExecutionRuntimeId runtimeId,
        RuntimeWorkerId workerId,
        ClaimTokenHash claimTokenHash,
        FencingToken fencingToken,
        ExecutionPrincipalSnapshot executionPrincipal,
        PolicySnapshotId policySnapshotId,
        TaskFactHash policySnapshotHash,
        SafetyEnforcementOverlayReference safetyOverlay,
        Set<String> allowedTools,
        Set<TaskProviderAuthorization> providerAuthorizations) {

    private static final int MAX_PROVIDER_AUTHORIZATIONS = 200;

    public TaskTokenGrantScope {
        workItemScope = Objects.requireNonNull(workItemScope, "workItemScope");
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || attempt > TaskExecution.MAX_SUPPORTED_ATTEMPTS) {
            throw new DomainValidationException(
                    "taskToken.scope.attempt", "must be within the supported attempt range");
        }
        executionLeaseId = Objects.requireNonNull(executionLeaseId, "executionLeaseId");
        environment = Objects.requireNonNull(environment, "environment");
        runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        claimTokenHash = Objects.requireNonNull(claimTokenHash, "claimTokenHash");
        fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
        executionPrincipal = Objects.requireNonNull(executionPrincipal, "executionPrincipal");
        policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
        safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        allowedTools = PolicySnapshot.requireKeys(
                allowedTools, "taskToken.scope.allowedTools", false);
        providerAuthorizations = Set.copyOf(Objects.requireNonNull(
                providerAuthorizations, "providerAuthorizations"));
        if (providerAuthorizations.size() > MAX_PROVIDER_AUTHORIZATIONS) {
            throw new DomainValidationException(
                    "taskToken.scope.providerAuthorizations", "must not exceed 200 values");
        }
        Set<ProviderBindingId> bindingIds = new HashSet<>();
        if (providerAuthorizations.stream()
                .map(TaskProviderAuthorization::bindingId)
                .anyMatch(bindingId -> !bindingIds.add(bindingId))) {
            throw new DomainValidationException(
                    "taskToken.scope.providerAuthorizations",
                    "must contain at most one authorization per ProviderBinding");
        }
    }

    static TaskTokenGrantScope issue(
            TaskExecution execution,
            ExecutionLease lease,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            Set<String> requestedTools,
            Collection<TaskProviderGrantRequest> providerRequests,
            UtcTimestamp issuedAt) {
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        ExecutionLease requiredLease = Objects.requireNonNull(lease, "lease");
        PolicySnapshot requiredPolicy = Objects.requireNonNull(policy, "policy");
        SafetyEnforcementOverlay requiredOverlay = Objects.requireNonNull(overlay, "overlay");
        UtcTimestamp requiredIssuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        requireIssuableExecution(requiredExecution);
        requireCurrentPlanningContext(requiredExecution, requiredPolicy, requiredOverlay);

        Set<String> tools = PolicySnapshot.requireKeys(
                requestedTools, "taskToken.scope.allowedTools", false);
        if (!requiredOverlay.permits(requiredPolicy, Set.of(), tools)) {
            throw new DomainValidationException(
                    "taskToken.scope.allowedTools",
                    "must be allowed by the current Policy and Safety overlay");
        }

        requireLease(requiredExecution, requiredLease, requiredIssuedAt);
        Set<TaskProviderAuthorization> authorizations = Objects.requireNonNull(
                        providerRequests, "providerRequests")
                .stream()
                .map(request -> TaskProviderAuthorization.issue(
                        requiredExecution.scope(), requiredPolicy, request))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (authorizations.size() != providerRequests.size()) {
            throw new DomainValidationException(
                    "taskToken.scope.providerAuthorizations",
                    "must not contain duplicate Provider authorization requests");
        }

        return new TaskTokenGrantScope(
                requiredExecution.scope(),
                requiredExecution.taskId(),
                requiredExecution.id(),
                requiredExecution.attempt(),
                requiredLease.id(),
                requiredLease.environment(),
                requiredLease.runtimeId(),
                requiredLease.workerId(),
                requiredLease.claimTokenHash(),
                requiredLease.fencingToken(),
                requiredPolicy.executionPrincipal(),
                requiredPolicy.id(),
                requiredPolicy.snapshotHash(),
                requiredOverlay.reference(),
                tools,
                authorizations);
    }

    /** Verifies that the currently persisted Lease still owns every coordinate in this scope. */
    public void requireActiveLease(ExecutionLease lease, UtcTimestamp authoritativeNow) {
        ExecutionLease requiredLease = Objects.requireNonNull(lease, "lease");
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        boolean matches = executionLeaseId.equals(requiredLease.id())
                && workItemScope.organizationId().equals(requiredLease.organizationId())
                && environment.equals(requiredLease.environment())
                && taskExecutionId.equals(requiredLease.taskExecutionId())
                && attempt == requiredLease.attempt()
                && runtimeId.equals(requiredLease.runtimeId())
                && workerId.equals(requiredLease.workerId())
                && claimTokenHash.equals(requiredLease.claimTokenHash())
                && fencingToken.equals(requiredLease.fencingToken());
        if (!matches || !requiredLease.isActiveAt(now)) {
            throw new DomainValidationException(
                    "taskToken.scope.executionLeaseId",
                    "must match every coordinate of the current active Lease");
        }
    }

    /** Checks one exact Tool and optional Provider resource use against the minimum grant. */
    public void requireAllowed(TaskTokenAccessRequest request) {
        TaskTokenAccessRequest required = Objects.requireNonNull(request, "request");
        if (!allowedTools.contains(required.tool())) {
            throw new DomainValidationException(
                    "taskTokenAccess.tool", "must be contained in the Task Token scope");
        }
        Optional<TaskProviderAccessRequest> providerAccess = required.providerAccess();
        if (providerAccess.isEmpty()) {
            return;
        }
        TaskProviderAccessRequest access = providerAccess.orElseThrow();
        boolean allowed = providerAuthorizations.stream()
                .filter(authorization -> authorization.bindingId().equals(access.bindingId()))
                .anyMatch(authorization -> authorization.allows(
                        access.capability(), access.resource()));
        if (!allowed) {
            throw new DomainValidationException(
                    "taskTokenAccess.providerAccess",
                    "must be contained in the ProviderBinding capability and resource scope");
        }
    }

    private static void requireIssuableExecution(TaskExecution execution) {
        if (execution.status() != TaskExecutionStatus.CLAIMED
                && execution.status() != TaskExecutionStatus.PREPARING
                && execution.status() != TaskExecutionStatus.RUNNING) {
            throw new DomainValidationException(
                    "taskToken.scope.taskExecutionId",
                    "must reference a claimed, preparing or running TaskExecution");
        }
    }

    private static void requireCurrentPlanningContext(
            TaskExecution execution,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay) {
        TaskExecutionPlanningContext context = execution.planningContext().orElseThrow(() ->
                new DomainValidationException(
                        "taskToken.scope.policySnapshotId",
                        "requires an initialized TaskExecution planning context"));
        boolean matches = execution.scope().equals(policy.scope())
                && execution.scope().equals(overlay.scope())
                && execution.taskId().equals(policy.taskId())
                && execution.taskId().equals(overlay.taskId())
                && execution.id().equals(policy.executionId())
                && execution.id().equals(overlay.executionId())
                && context.executionPrincipal().equals(policy.executionPrincipal())
                && context.policySnapshotId().equals(policy.id())
                && context.policySnapshotHash().equals(policy.snapshotHash())
                && context.safetyOverlay().equals(overlay.reference());
        if (!matches) {
            throw new DomainValidationException(
                    "taskToken.scope.policySnapshotId",
                    "must match the TaskExecution current Policy and Safety overlay");
        }
    }

    private static void requireLease(
            TaskExecution execution, ExecutionLease lease, UtcTimestamp issuedAt) {
        boolean matches = execution.scope().organizationId().equals(lease.organizationId())
                && execution.id().equals(lease.taskExecutionId())
                && execution.attempt() == lease.attempt()
                && execution.lastFencingToken().filter(lease.fencingToken()::equals).isPresent();
        if (!matches || !lease.isActiveAt(issuedAt)) {
            throw new DomainValidationException(
                    "taskToken.scope.executionLeaseId",
                    "must reference the TaskExecution current active Lease");
        }
    }

    @Override
    public String toString() {
        return "TaskTokenGrantScope[organizationId=" + workItemScope.organizationId()
                + ", taskId=" + taskId
                + ", taskExecutionId=" + taskExecutionId
                + ", attempt=" + attempt
                + ", executionLeaseId=" + executionLeaseId
                + ", runtimeId=" + runtimeId
                + ", workerId=" + workerId
                + ", tools=" + allowedTools.size()
                + ", providerAuthorizations=" + providerAuthorizations.size()
                + ", sensitiveCoordinates=[REDACTED]]";
    }
}
