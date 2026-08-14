package io.crewscope.domain.task;

import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeProfile;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerCapacity;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class TaskCredentialGrantDomainFixture {

    static final UtcTimestamp POLICY_AT = UtcTimestamp.parse("2026-08-13T08:01:00Z");
    static final UtcTimestamp READY_AT = UtcTimestamp.parse("2026-08-13T08:02:00Z");
    static final UtcTimestamp CLAIM_AT = UtcTimestamp.parse("2026-08-13T08:03:00Z");
    static final UtcTimestamp ISSUED_AT = UtcTimestamp.parse("2026-08-13T08:03:10Z");
    static final UtcTimestamp USED_AT = UtcTimestamp.parse("2026-08-13T08:03:20Z");
    static final UtcTimestamp EXPIRES_AT = UtcTimestamp.parse("2026-08-13T08:08:10Z");
    static final UtcTimestamp LEASE_EXPIRES_AT = UtcTimestamp.parse("2026-08-13T08:10:00Z");
    static final String JTI_VALUE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    static final String OTHER_JTI_VALUE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq";
    static final String RESOURCE = "repository:crewscope";

    final TaskDomainFixture base = new TaskDomainFixture();
    final Task task = base.task();
    final RuntimeEnvironment environment = new RuntimeEnvironment("production");
    final RuntimeCapabilities runtimeCapabilities = RuntimeCapabilities.of(
            Set.of(RuntimeCapability.CONVERSATION, RuntimeCapability.PLAN),
            Set.of("java"),
            Set.of("maven"));
    final ProviderBinding binding = providerBinding();
    final TaskExecution initialExecution = TaskExecution.firstAttempt(
            TaskExecutionId.generate(),
            task,
            3,
            TaskExecutionPriority.NORMAL,
            READY_AT,
            base.owner,
            TaskDomainFixture.CREATED_AT);
    final PolicySnapshot policy = PolicySnapshot.initial(
            PolicySnapshotId.generate(),
            task,
            initialExecution,
            base.executor,
            new PolicyPackReference(PolicyPackId.generate(), 1),
            AgentProfileId.generate(),
            1,
            Set.of(ExecutionCapability.PLAN),
            Set.of("repository.read", "validation.run"),
            Set.of(binding.id()),
            new PolicyBudget(100_000, 20, 50, 3_600),
            base.owner,
            POLICY_AT);
    final SafetyEnforcementOverlay overlay = SafetyEnforcementOverlay.unrestricted(
            SafetyEnforcementOverlayId.generate(), task, initialExecution, base.owner, POLICY_AT);
    final TaskExecution claimedExecution = initialExecution
            .initializePlanningContext(policy, overlay, 0, base.owner, POLICY_AT)
            .markReady(1, base.owner, READY_AT)
            .claim(2, base.executor, CLAIM_AT);
    final ExecutionRuntime runtime = ExecutionRuntime.register(
            ExecutionRuntimeId.generate(),
            base.scope.organizationId(),
            environment,
            "agentscope-java",
            "AgentScope Java",
            "2.0.0",
            runtimeCapabilities,
            base.owner,
            TaskDomainFixture.CREATED_AT);
    final RuntimeWorker worker = RuntimeWorker.register(
                    RuntimeWorkerId.generate(),
                    runtime,
                    "crewscope-worker-01",
                    RuntimeProfile.WORKER,
                    runtimeCapabilities,
                    new RuntimeWorkerCapacity(4, 0),
                    base.executor,
                    TaskDomainFixture.CREATED_AT)
            .activate(0, base.executor, POLICY_AT);
    final ClaimToken claimToken = new ClaimToken(JTI_VALUE);
    final ExecutionLease lease = ExecutionLease.acquire(
            ExecutionLeaseId.generate(),
            claimedExecution,
            runtime,
            worker,
            runtimeCapabilities,
            Duration.ofMinutes(2),
            claimToken,
            CLAIM_AT,
            LEASE_EXPIRES_AT);

    TaskCredentialIssuance issue() {
        return TaskCredentialGrant.issue(
                TaskCredentialGrantId.generate(),
                claimedExecution,
                lease,
                policy,
                overlay,
                Set.of("repository.read"),
                List.of(providerRequest()),
                new TaskTokenJti(JTI_VALUE),
                EXPIRES_AT,
                base.executor,
                ISSUED_AT);
    }

    TaskProviderGrantRequest providerRequest() {
        return new TaskProviderGrantRequest(
                binding,
                new ProviderAccessScope(
                        ProviderCapabilities.of("repository.read"),
                        ProviderResourceScope.of(RESOURCE)));
    }

    TaskTokenAccessRequest providerAccess() {
        return TaskTokenAccessRequest.provider(
                "repository.read",
                new TaskProviderAccessRequest(
                        binding.id(),
                        new io.crewscope.domain.provider.ProviderCapability("repository.read"),
                        RESOURCE));
    }

    ProviderBinding providerBinding() {
        ProviderBindingTarget target = new ProviderBindingTarget(
                base.scope.organizationId(),
                base.scope.teamId(),
                base.scope.workspaceId(),
                ProviderBindingTargetType.WORK_PROJECT,
                Optional.of(base.scope.projectId()));
        return ProviderBinding.reconstitute(
                ProviderBindingId.generate(),
                base.scope.organizationId(),
                target,
                ProviderOwner.organization(base.scope.organizationId()),
                ProviderDefinitionId.generate(),
                2,
                ProviderType.SOURCE_CODE,
                ProviderImplementationId.generate(),
                3,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new ProviderAccessScope(
                        ProviderCapabilities.of("repository.read", "repository.write"),
                        ProviderResourceScope.of(RESOURCE, "repository:other")),
                false,
                ProviderRegistrationStatus.ACTIVE,
                4,
                AuditMetadata.createdBy(base.owner.id(), TaskDomainFixture.CREATED_AT));
    }
}
