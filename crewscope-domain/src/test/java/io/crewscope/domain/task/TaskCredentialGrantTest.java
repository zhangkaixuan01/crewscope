package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderCapability;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskCredentialGrantTest {

    @Test
    void issuesClaimsClosedOverLeasePolicyPrincipalToolsAndProviderScope() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();

        TaskCredentialIssuance issuance = fixture.issue();
        TaskCredentialGrant grant = issuance.grant();
        TaskTokenClaims claims = issuance.claims();

        assertEquals(TaskCredentialGrantStatus.ACTIVE, grant.status());
        assertEquals(0, grant.version());
        assertEquals(0, grant.useCount());
        assertEquals(grant.id(), claims.grantId());
        assertEquals(grant.jtiHash(), claims.jti().hash());
        assertEquals(grant.scope(), claims.scope());
        assertEquals(fixture.base.scope, grant.scope().workItemScope());
        assertEquals(fixture.task.id(), grant.scope().taskId());
        assertEquals(fixture.claimedExecution.id(), grant.scope().taskExecutionId());
        assertEquals(fixture.lease.id(), grant.scope().executionLeaseId());
        assertEquals(fixture.runtime.id(), grant.scope().runtimeId());
        assertEquals(fixture.worker.id(), grant.scope().workerId());
        assertEquals(fixture.policy.executionPrincipal(), grant.scope().executionPrincipal());
        assertEquals(fixture.policy.id(), grant.scope().policySnapshotId());
        assertEquals(fixture.overlay.reference(), grant.scope().safetyOverlay());
        assertEquals(Set.of("repository.read"), grant.scope().allowedTools());
        TaskProviderAuthorization authorization =
                grant.scope().providerAuthorizations().iterator().next();
        assertEquals(fixture.binding.id(), authorization.bindingId());
        assertEquals(fixture.binding.version(), authorization.bindingVersion());
        assertEquals(Set.of(TaskCredentialGrantDomainFixture.RESOURCE),
                authorization.resources().resources());
    }

    @Test
    void recordsAuthorizedToolAndProviderUseWithOptimisticVersion() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();

        TaskCredentialGrant used = issuance.grant().use(
                issuance.claims(), fixture.lease, fixture.providerAccess(), 0,
                TaskCredentialGrantDomainFixture.USED_AT);

        assertEquals(1, used.useCount());
        assertEquals(TaskCredentialGrantDomainFixture.USED_AT, used.lastUsedAt().orElseThrow());
        assertEquals(1, used.version());
        assertEquals(fixture.base.executor.id(), used.audit().updatedBy().orElseThrow());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> used.use(
                        issuance.claims(), fixture.lease, fixture.providerAccess(), 0,
                        UtcTimestamp.parse("2026-08-13T08:03:30Z")));
    }

    @Test
    void enforcesExactExpiryBoundaryAndExplicitExpiryLifecycle() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();

        assertThrows(
                DomainValidationException.class,
                () -> issuance.grant().use(
                        issuance.claims(), fixture.lease,
                        TaskTokenAccessRequest.tool("repository.read"), 0,
                        TaskCredentialGrantDomainFixture.EXPIRES_AT));
        assertThrows(
                DomainValidationException.class,
                () -> issuance.grant().expire(
                        0, fixture.base.owner, TaskCredentialGrantDomainFixture.USED_AT));

        TaskCredentialGrant expired = issuance.grant().expire(
                0, fixture.base.owner, TaskCredentialGrantDomainFixture.EXPIRES_AT);

        assertEquals(TaskCredentialGrantStatus.EXPIRED, expired.status());
        assertEquals("TASK_TOKEN_EXPIRED", expired.termination().orElseThrow().reason());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> expired.use(
                        issuance.claims(), fixture.lease,
                        TaskTokenAccessRequest.tool("repository.read"), 1,
                        TaskCredentialGrantDomainFixture.EXPIRES_AT));
    }

    @Test
    void revokesBeforeExpiryAndMakesTerminalFactImmutable() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();

        TaskCredentialGrant revoked = issuance.grant().revoke(
                0, fixture.base.owner, "Worker ownership lost",
                TaskCredentialGrantDomainFixture.USED_AT);

        assertEquals(TaskCredentialGrantStatus.REVOKED, revoked.status());
        assertEquals(1, revoked.version());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> revoked.revoke(
                        1, fixture.base.owner, "Again",
                        UtcTimestamp.parse("2026-08-13T08:03:30Z")));
        assertThrows(
                DomainValidationException.class,
                () -> issuance.grant().revoke(
                        0, fixture.base.owner, "Too late",
                        TaskCredentialGrantDomainFixture.EXPIRES_AT));
    }

    @Test
    void rejectsClaimsWithWrongGrantJtiScopeOrTimeline() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();
        TaskTokenClaims claims = issuance.claims();
        TaskTokenClaims[] invalid = {
            new TaskTokenClaims(
                    TaskTokenClaims.AUDIENCE, TaskCredentialGrantId.generate(), claims.jti(),
                    claims.scope(), claims.issuedAt(), claims.expiresAt()),
            new TaskTokenClaims(
                    TaskTokenClaims.AUDIENCE, claims.grantId(),
                    new TaskTokenJti(TaskCredentialGrantDomainFixture.OTHER_JTI_VALUE),
                    claims.scope(), claims.issuedAt(), claims.expiresAt()),
            new TaskTokenClaims(
                    TaskTokenClaims.AUDIENCE, claims.grantId(), claims.jti(),
                    scopeWith(claims.scope(), fixture, fixture.lease),
                    claims.issuedAt(), claims.expiresAt()),
            new TaskTokenClaims(
                    TaskTokenClaims.AUDIENCE, claims.grantId(), claims.jti(), claims.scope(),
                    UtcTimestamp.parse("2026-08-13T08:03:11Z"), claims.expiresAt())
        };

        for (TaskTokenClaims invalidClaims : invalid) {
            assertThrows(
                    DomainValidationException.class,
                    () -> issuance.grant().use(
                            invalidClaims, fixture.lease,
                            TaskTokenAccessRequest.tool("repository.read"), 0,
                            TaskCredentialGrantDomainFixture.USED_AT));
        }
    }

    @Test
    void rejectsWrongLeaseExecutionAttemptRuntimeWorkerClaimAndFencingCoordinates() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();
        ExecutionLease lease = fixture.lease;
        ExecutionLease[] invalid = {
            leaseWith(lease, ExecutionLeaseId.generate(), lease.taskExecutionId(), lease.attempt(),
                    lease.runtimeId(), lease.workerId(), lease.claimTokenHash(), lease.fencingToken()),
            leaseWith(lease, lease.id(), TaskExecutionId.generate(), lease.attempt(),
                    lease.runtimeId(), lease.workerId(), lease.claimTokenHash(), lease.fencingToken()),
            leaseWith(lease, lease.id(), lease.taskExecutionId(), lease.attempt() + 1,
                    lease.runtimeId(), lease.workerId(), lease.claimTokenHash(), lease.fencingToken()),
            leaseWith(lease, lease.id(), lease.taskExecutionId(), lease.attempt(),
                    ExecutionRuntimeId.generate(), lease.workerId(), lease.claimTokenHash(),
                    lease.fencingToken()),
            leaseWith(lease, lease.id(), lease.taskExecutionId(), lease.attempt(),
                    lease.runtimeId(), RuntimeWorkerId.generate(), lease.claimTokenHash(),
                    lease.fencingToken()),
            leaseWith(lease, lease.id(), lease.taskExecutionId(), lease.attempt(),
                    lease.runtimeId(), lease.workerId(), new ClaimTokenHash("0".repeat(64)),
                    lease.fencingToken()),
            leaseWith(lease, lease.id(), lease.taskExecutionId(), lease.attempt(),
                    lease.runtimeId(), lease.workerId(), lease.claimTokenHash(),
                    lease.fencingToken().next())
        };

        for (ExecutionLease invalidLease : invalid) {
            assertThrows(
                    DomainValidationException.class,
                    () -> issuance.grant().use(
                            issuance.claims(), invalidLease,
                            TaskTokenAccessRequest.tool("repository.read"), 0,
                            TaskCredentialGrantDomainFixture.USED_AT));
        }
    }

    @Test
    void rejectsToolBindingCapabilityAndResourceOutsideMinimumScope() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();
        TaskTokenAccessRequest[] invalid = {
            TaskTokenAccessRequest.tool("validation.run"),
            TaskTokenAccessRequest.provider(
                    "repository.read",
                    new TaskProviderAccessRequest(
                            ProviderBindingId.generate(), new ProviderCapability("repository.read"),
                            TaskCredentialGrantDomainFixture.RESOURCE)),
            TaskTokenAccessRequest.provider(
                    "repository.read",
                    new TaskProviderAccessRequest(
                            fixture.binding.id(), new ProviderCapability("repository.write"),
                            TaskCredentialGrantDomainFixture.RESOURCE)),
            TaskTokenAccessRequest.provider(
                    "repository.read",
                    new TaskProviderAccessRequest(
                            fixture.binding.id(), new ProviderCapability("repository.read"),
                            "repository:other"))
        };

        for (TaskTokenAccessRequest invalidRequest : invalid) {
            assertThrows(
                    DomainValidationException.class,
                    () -> issuance.grant().use(
                            issuance.claims(), fixture.lease, invalidRequest, 0,
                            TaskCredentialGrantDomainFixture.USED_AT));
        }
    }

    @Test
    void rejectsPolicySafetyBindingScopeAndUnrestrictedProviderExpansion() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        assertThrows(
                DomainValidationException.class,
                () -> issue(fixture, Set.of("unknown.tool"), List.of(fixture.providerRequest()),
                        fixture.policy, fixture.overlay));

        SafetyEnforcementOverlay tightened = fixture.overlay.tighten(
                Set.of(), Set.of(), Set.of("repository.read"), fixture.base.owner,
                UtcTimestamp.parse("2026-08-13T08:01:30Z"));
        TaskExecution tightenedExecution = fixture.initialExecution
                .initializePlanningContext(fixture.policy, fixture.overlay, 0, fixture.base.owner,
                        TaskCredentialGrantDomainFixture.POLICY_AT)
                .tightenSafetyOverlay(tightened, fixture.overlay, 1, fixture.base.owner,
                        UtcTimestamp.parse("2026-08-13T08:01:30Z"))
                .markReady(2, fixture.base.owner, TaskCredentialGrantDomainFixture.READY_AT)
                .claim(3, fixture.base.executor, TaskCredentialGrantDomainFixture.CLAIM_AT);
        assertThrows(
                DomainValidationException.class,
                () -> TaskTokenGrantScope.issue(
                        tightenedExecution, fixture.lease, fixture.policy, tightened,
                        Set.of("repository.read"), List.of(fixture.providerRequest()),
                        TaskCredentialGrantDomainFixture.ISSUED_AT));

        ProviderBinding outsidePolicy = fixture.providerBinding();
        assertThrows(
                DomainValidationException.class,
                () -> issue(fixture, Set.of("repository.read"),
                        List.of(new TaskProviderGrantRequest(
                                outsidePolicy, outsidePolicy.effectiveAccess())),
                        fixture.policy, fixture.overlay));
        ProviderBinding crossTeam = ProviderBinding.reconstitute(
                fixture.binding.id(),
                fixture.binding.organizationId(),
                new ProviderBindingTarget(
                        fixture.base.scope.organizationId(),
                        TeamId.generate(),
                        fixture.base.scope.workspaceId(),
                        fixture.binding.target().type(),
                        fixture.binding.target().workProjectId()),
                fixture.binding.owner(),
                fixture.binding.definitionId(),
                fixture.binding.definitionVersion(),
                fixture.binding.providerType(),
                fixture.binding.implementationId(),
                fixture.binding.implementationVersion(),
                fixture.binding.connectionId(),
                fixture.binding.connectionVersion(),
                fixture.binding.connectionGrantId(),
                fixture.binding.connectionGrantVersion(),
                fixture.binding.executionIdentity(),
                fixture.binding.effectiveAccess(),
                fixture.binding.defaultUsage(),
                fixture.binding.status(),
                fixture.binding.version(),
                fixture.binding.audit());
        assertThrows(
                DomainValidationException.class,
                () -> issue(fixture, Set.of("repository.read"),
                        List.of(new TaskProviderGrantRequest(
                                crossTeam, fixture.providerRequest().requestedAccess())),
                        fixture.policy, fixture.overlay));
        assertThrows(
                DomainValidationException.class,
                () -> issue(fixture, Set.of("repository.read"),
                        List.of(new TaskProviderGrantRequest(
                                fixture.binding,
                                new ProviderAccessScope(
                                        ProviderCapabilities.of("repository.read"),
                                        ProviderResourceScope.allResources()))),
                        fixture.policy, fixture.overlay));
    }

    @Test
    void rejectsInactiveOrVersionDuplicateBindingAuthorization() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        ProviderBinding inactive = copyBinding(
                fixture.binding, fixture.binding.id(), ProviderRegistrationStatus.DISABLED);
        assertThrows(
                DomainValidationException.class,
                () -> issue(fixture, Set.of("repository.read"),
                        List.of(new TaskProviderGrantRequest(
                                inactive, inactive.effectiveAccess())), fixture.policy,
                        fixture.overlay));

        ProviderAccessScope narrower = new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"),
                ProviderResourceScope.of(TaskCredentialGrantDomainFixture.RESOURCE));
        assertThrows(
                DomainValidationException.class,
                () -> issue(fixture, Set.of("repository.read"),
                        List.of(
                                new TaskProviderGrantRequest(fixture.binding, narrower),
                                new TaskProviderGrantRequest(fixture.binding,
                                        new ProviderAccessScope(
                                                ProviderCapabilities.of("repository.write"),
                                                ProviderResourceScope.of(
                                                        TaskCredentialGrantDomainFixture.RESOURCE)))),
                        fixture.policy, fixture.overlay));
    }

    @Test
    void rejectsTokenThatOutlivesLeaseAndInvalidLifetimeOrAudience() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        assertThrows(
                DomainValidationException.class,
                () -> TaskCredentialGrant.issue(
                        TaskCredentialGrantId.generate(), fixture.claimedExecution, fixture.lease,
                        fixture.policy, fixture.overlay, Set.of("repository.read"),
                        List.of(fixture.providerRequest()),
                        new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE),
                        UtcTimestamp.parse("2026-08-13T08:10:01Z"), fixture.base.executor,
                        TaskCredentialGrantDomainFixture.ISSUED_AT));
        assertThrows(
                DomainValidationException.class,
                () -> new TaskTokenClaims(
                        "wrong-audience", TaskCredentialGrantId.generate(),
                        new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE),
                        fixture.issue().grant().scope(), TaskCredentialGrantDomainFixture.ISSUED_AT,
                        TaskCredentialGrantDomainFixture.EXPIRES_AT));
        assertThrows(
                DomainValidationException.class,
                () -> new TaskTokenClaims(
                        TaskTokenClaims.AUDIENCE, TaskCredentialGrantId.generate(),
                        new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE),
                        fixture.issue().grant().scope(), TaskCredentialGrantDomainFixture.ISSUED_AT,
                        UtcTimestamp.parse("2026-08-13T08:03:14Z")));
    }

    @Test
    void rejectsImpossibleReconstitutedLifecycleShapes() {
        TaskCredentialGrantDomainFixture fixture = new TaskCredentialGrantDomainFixture();
        TaskCredentialIssuance issuance = fixture.issue();
        TaskCredentialGrant grant = issuance.grant();
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(grant, TaskCredentialGrantStatus.ACTIVE, 1,
                        Optional.empty(), Optional.empty(), grant.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(grant, TaskCredentialGrantStatus.REVOKED, 0,
                        Optional.empty(), Optional.empty(), grant.audit()));
        assertThrows(
                DomainValidationException.class,
                () -> TaskCredentialGrant.reconstitute(
                        grant.id(), grant.jtiHash(), grant.scope(), grant.issuedAt(),
                        grant.expiresAt(), TaskCredentialGrantStatus.ACTIVE, 2,
                        Optional.of(TaskCredentialGrantDomainFixture.USED_AT), Optional.empty(), 0,
                        grant.audit().modifiedBy(
                                fixture.base.executor.id(), TaskCredentialGrantDomainFixture.USED_AT)));
        assertThrows(
                DomainValidationException.class,
                () -> reconstitute(grant, TaskCredentialGrantStatus.EXPIRED, 0,
                        Optional.empty(),
                        Optional.of(new TaskCredentialGrantTermination(
                                TaskCredentialGrantStatus.EXPIRED, fixture.base.owner.id(),
                                TaskCredentialGrantDomainFixture.USED_AT, "Early")),
                        AuditMetadata.createdBy(fixture.base.owner.id(), grant.issuedAt())
                                .modifiedBy(fixture.base.owner.id(),
                                        TaskCredentialGrantDomainFixture.USED_AT)));
        TaskCredentialGrant used = grant.use(
                issuance.claims(), fixture.lease,
                TaskTokenAccessRequest.tool("repository.read"), 0,
                TaskCredentialGrantDomainFixture.USED_AT);
        UtcTimestamp earlyTermination = UtcTimestamp.parse("2026-08-13T08:03:15Z");
        assertThrows(
                DomainValidationException.class,
                () -> TaskCredentialGrant.reconstitute(
                        used.id(), used.jtiHash(), used.scope(), used.issuedAt(), used.expiresAt(),
                        TaskCredentialGrantStatus.REVOKED, used.useCount(), used.lastUsedAt(),
                        Optional.of(new TaskCredentialGrantTermination(
                                TaskCredentialGrantStatus.REVOKED, fixture.base.owner.id(),
                                earlyTermination, "Before last use")),
                        2,
                        AuditMetadata.createdBy(fixture.base.executor.id(), used.issuedAt())
                                .modifiedBy(fixture.base.owner.id(), earlyTermination)));
    }

    private static TaskCredentialIssuance issue(
            TaskCredentialGrantDomainFixture fixture,
            Set<String> tools,
            List<TaskProviderGrantRequest> providerRequests,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay) {
        return TaskCredentialGrant.issue(
                TaskCredentialGrantId.generate(), fixture.claimedExecution, fixture.lease, policy,
                overlay, tools, providerRequests,
                new TaskTokenJti(TaskCredentialGrantDomainFixture.JTI_VALUE),
                TaskCredentialGrantDomainFixture.EXPIRES_AT, fixture.base.executor,
                TaskCredentialGrantDomainFixture.ISSUED_AT);
    }

    private static TaskTokenGrantScope scopeWith(
            TaskTokenGrantScope source,
            TaskCredentialGrantDomainFixture fixture,
            ExecutionLease lease) {
        return new TaskTokenGrantScope(
                source.workItemScope(), source.taskId(), source.taskExecutionId(), source.attempt(),
                source.executionLeaseId(), source.environment(), source.runtimeId(),
                RuntimeWorkerId.generate(), source.claimTokenHash(), source.fencingToken(),
                source.executionPrincipal(), source.policySnapshotId(), source.policySnapshotHash(),
                source.safetyOverlay(), source.allowedTools(), source.providerAuthorizations());
    }

    private static ExecutionLease leaseWith(
            ExecutionLease source,
            ExecutionLeaseId id,
            TaskExecutionId executionId,
            int attempt,
            ExecutionRuntimeId runtimeId,
            RuntimeWorkerId workerId,
            ClaimTokenHash claimTokenHash,
            FencingToken fencingToken) {
        return ExecutionLease.reconstitute(
                id, source.organizationId(), source.environment(), executionId, attempt, runtimeId,
                workerId, claimTokenHash, fencingToken, source.phase(), source.acquiredAt(),
                source.lastHeartbeatAt(), source.expiresAt(), source.release(), source.version());
    }

    private static ProviderBinding copyBinding(
            ProviderBinding source,
            ProviderBindingId id,
            ProviderRegistrationStatus status) {
        return ProviderBinding.reconstitute(
                id, source.organizationId(), source.target(), source.owner(), source.definitionId(),
                source.definitionVersion(), source.providerType(), source.implementationId(),
                source.implementationVersion(), source.connectionId(), source.connectionVersion(),
                source.connectionGrantId(), source.connectionGrantVersion(),
                source.executionIdentity(), source.effectiveAccess(), source.defaultUsage(), status,
                source.version(), source.audit());
    }

    private static TaskCredentialGrant reconstitute(
            TaskCredentialGrant source,
            TaskCredentialGrantStatus status,
            long useCount,
            Optional<UtcTimestamp> lastUsedAt,
            Optional<TaskCredentialGrantTermination> termination,
            AuditMetadata audit) {
        return TaskCredentialGrant.reconstitute(
                source.id(), source.jtiHash(), source.scope(), source.issuedAt(), source.expiresAt(),
                status, useCount, lastUsedAt, termination, source.version(), audit);
    }
}
