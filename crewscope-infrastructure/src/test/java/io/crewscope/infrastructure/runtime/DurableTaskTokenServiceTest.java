package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.DecodedTaskToken;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.task.TaskTokenCodec;
import io.crewscope.application.task.TaskTokenIssueCommand;
import io.crewscope.application.task.TaskTokenIssueResult;
import io.crewscope.application.task.TaskTokenJtiGenerator;
import io.crewscope.application.task.TaskTokenRotateCommand;
import io.crewscope.application.task.TaskTokenScopeFingerprint;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskCredentialGrantStatus;
import io.crewscope.domain.task.TaskTokenClaims;
import io.crewscope.domain.task.TaskTokenAccessRequest;
import io.crewscope.domain.task.TaskTokenGrantScope;
import io.crewscope.domain.task.TaskProviderAuthorization;
import io.crewscope.domain.task.TaskProviderAccessRequest;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderCapability;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.task.TaskTokenJti;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Issuance, Lease-bounded lifetime, scope-narrow rotation and stale token tests. */
class DurableTaskTokenServiceTest {

    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final ExecutionLeaseRepository leases = mock(ExecutionLeaseRepository.class);
    private final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
    private final SafetyEnforcementOverlayRepository overlays =
            mock(SafetyEnforcementOverlayRepository.class);
    private final TaskCredentialGrantRepository grants = mock(TaskCredentialGrantRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final ProviderBindingRepository bindings = mock(ProviderBindingRepository.class);
    private final ConnectionGrantRepository connectionGrants = mock(ConnectionGrantRepository.class);
    private final TaskTokenCodec codec = mock(TaskTokenCodec.class);
    private final TaskTokenJtiGenerator jtis = mock(TaskTokenJtiGenerator.class);
    private final AtomicReference<io.crewscope.domain.shared.time.UtcTimestamp> now =
            new AtomicReference<>();
    private TaskTokenRuntimeFixture fixture;
    private DurableTaskTokenService service;

    @BeforeEach
    void setUp() {
        fixture = new TaskTokenRuntimeFixture();
        now.set(fixture.now);
        when(executions.findById(fixture.organizationId, fixture.executionId))
                .thenReturn(Optional.of(fixture.execution));
        when(tasks.findById(fixture.organizationId, fixture.taskId))
                .thenReturn(Optional.of(fixture.task));
        when(leases.findById(fixture.organizationId, fixture.environment, fixture.leaseId))
                .thenReturn(Optional.of(fixture.lease));
        when(policies.findById(fixture.organizationId, fixture.policyId))
                .thenReturn(Optional.of(fixture.policy));
        when(overlays.findByIdAndVersion(
                fixture.organizationId, fixture.overlayReference.id(),
                fixture.overlayReference.version())).thenReturn(Optional.of(fixture.overlay));
        when(principals.findById(fixture.organizationId, fixture.executor.id()))
                .thenReturn(Optional.of(fixture.executor));
        when(principals.findById(fixture.organizationId, fixture.owner.id()))
                .thenReturn(Optional.of(fixture.owner));
        when(members.findByTeamAndUserPrincipalId(
                        fixture.organizationId, fixture.workScope.teamId(), fixture.owner.id()))
                .thenReturn(Optional.of(fixture.ownerMembership));
        when(assignments.findById(
                        fixture.organizationId, fixture.executionPrincipal.assignmentId()))
                .thenReturn(Optional.of(fixture.assignment));
        when(grants.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(grants.rotate(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(grants.recordUse(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(codec.encode(any())).thenReturn("signed-token", "rotated-token");
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        AuthoritativeTimeProvider timeProvider = now::get;
        TaskTokenCurrentAuthorization currentAuthorization = new TaskTokenCurrentAuthorization(
                executions, tasks, principals, assignments, members);
        service = new DurableTaskTokenService(
                executions, leases, policies, overlays, grants, currentAuthorization, bindings,
                connectionGrants, transactions, timeProvider, jtis, codec,
                new TaskTokenServiceSpec(
                        fixture.organizationId, fixture.environment, fixture.actor));
    }

    @Test
    void issuesOneTimeTokenBoundedByTheCurrentLease() {
        TaskTokenJti jti = new TaskTokenJti(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        when(jtis.generate()).thenReturn(jti);

        TaskTokenIssueResult result = service.issue(new TaskTokenIssueCommand(
                fixture.executionId, fixture.leaseId,
                Set.of("repository.read"), List.of(), Duration.ofMinutes(5)));

        assertEquals(fixture.lease.expiresAt(), result.grant().expiresAt());
        assertEquals(fixture.executionId, result.context().scope().taskExecutionId());
        assertEquals("signed-token", result.token());
        assertFalse(result.toString().contains("signed-token"));
        assertFalse(result.grant().toString().contains(jti.reveal()));
        verify(grants).create(any(TaskCredentialGrant.class));
    }

    @Test
    void rejectsIssuanceWhenExecutorAssignmentWasAlreadyRevoked() {
        when(jtis.generate()).thenReturn(new TaskTokenJti(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"));
        when(fixture.assignment.isActive()).thenReturn(false);

        assertThrows(RuntimeException.class, () -> service.issue(new TaskTokenIssueCommand(
                fixture.executionId, fixture.leaseId,
                Set.of("repository.read"), List.of(), Duration.ofMinutes(1))));

        verify(grants, never()).create(any(TaskCredentialGrant.class));
        verify(codec, never()).encode(any());
    }

    @Test
    void rotatesAtomicallyWithNewJtiAndNoScopeExpansion() {
        TaskTokenJti oldJti = new TaskTokenJti(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        TaskTokenJti newJti = new TaskTokenJti(
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq");
        when(jtis.generate()).thenReturn(oldJti, newJti);
        TaskTokenIssueResult issued = service.issue(new TaskTokenIssueCommand(
                fixture.executionId, fixture.leaseId,
                Set.of("repository.read", "validation.run"),
                List.of(), Duration.ofSeconds(90)));
        now.set(io.crewscope.domain.shared.time.UtcTimestamp.parse("2026-08-15T03:00:10Z"));
        when(codec.decode("signed-token")).thenReturn(decoded(issued.grant(), oldJti));
        when(grants.findByJtiHash(fixture.organizationId, oldJti.hash()))
                .thenReturn(Optional.of(issued.grant()));

        TaskTokenIssueResult rotated = service.rotate(new TaskTokenRotateCommand(
                "signed-token", 0, Set.of("repository.read"),
                List.of(), Duration.ofSeconds(60)));

        ArgumentCaptor<TaskCredentialGrant> terminated =
                ArgumentCaptor.forClass(TaskCredentialGrant.class);
        ArgumentCaptor<TaskCredentialGrant> replacement =
                ArgumentCaptor.forClass(TaskCredentialGrant.class);
        verify(grants).rotate(terminated.capture(), replacement.capture());
        assertEquals(TaskCredentialGrantStatus.REVOKED, terminated.getValue().status());
        assertNotEquals(issued.grant().jtiHash(), replacement.getValue().jtiHash());
        assertEquals(Set.of("repository.read"), rotated.grant().scope().allowedTools());
        assertEquals("rotated-token", rotated.token());
    }

    @Test
    void rejectsRotationThatBroadensTheCurrentToolScope() {
        TaskTokenJti oldJti = new TaskTokenJti(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        TaskTokenJti newJti = new TaskTokenJti(
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq");
        when(jtis.generate()).thenReturn(oldJti, newJti);
        TaskTokenIssueResult issued = service.issue(new TaskTokenIssueCommand(
                fixture.executionId, fixture.leaseId,
                Set.of("repository.read"), List.of(), Duration.ofSeconds(90)));
        now.set(io.crewscope.domain.shared.time.UtcTimestamp.parse("2026-08-15T03:00:10Z"));
        when(codec.decode("signed-token")).thenReturn(decoded(issued.grant(), oldJti));
        when(grants.findByJtiHash(fixture.organizationId, oldJti.hash()))
                .thenReturn(Optional.of(issued.grant()));

        assertThrows(RuntimeException.class, () -> service.rotate(new TaskTokenRotateCommand(
                "signed-token", 0, Set.of("repository.read", "validation.run"),
                List.of(), Duration.ofSeconds(60))));
    }

    @Test
    void rechecksCurrentProviderBindingBeforeEveryAuthorizedUse() {
        TaskTokenJti jti = new TaskTokenJti(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        ProviderBindingId bindingId = ProviderBindingId.generate();
        TaskProviderAuthorization authorization = new TaskProviderAuthorization(
                bindingId, 4, Optional.empty(), Optional.empty(),
                ProviderCapabilities.of("repository.read"),
                ProviderResourceScope.of("repository:crewscope"));
        TaskTokenGrantScope base = new TaskTokenGrantScope(
                fixture.workScope, fixture.taskId, fixture.executionId, 1,
                fixture.leaseId, fixture.environment, fixture.runtimeId, fixture.workerId,
                fixture.claimTokenHash, fixture.fencingToken, fixture.executionPrincipal,
                fixture.policyId, fixture.policyHash, fixture.overlayReference,
                Set.of("repository.read"), Set.of(authorization));
        TaskCredentialGrant grant = TaskCredentialGrant.reconstitute(
                io.crewscope.domain.task.TaskCredentialGrantId.generate(),
                jti.hash(), base, fixture.now,
                io.crewscope.domain.shared.time.UtcTimestamp.from(
                        fixture.now.value().plusSeconds(60)),
                TaskCredentialGrantStatus.ACTIVE, 0, Optional.empty(), Optional.empty(), 0,
                AuditMetadata.createdBy(fixture.actor.id(), fixture.now));
        when(codec.decode("provider-token")).thenReturn(decoded(grant, jti));
        when(grants.findByJtiHash(fixture.organizationId, jti.hash()))
                .thenReturn(Optional.of(grant));
        ProviderBinding binding = mock(ProviderBinding.class);
        when(binding.status()).thenReturn(ProviderRegistrationStatus.ACTIVE);
        when(binding.version()).thenReturn(4L);
        when(binding.effectiveAccess()).thenReturn(new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"),
                ProviderResourceScope.of("repository:crewscope")));
        when(bindings.findById(fixture.organizationId, bindingId))
                .thenReturn(Optional.of(binding));
        TaskTokenAccessRequest access = TaskTokenAccessRequest.provider(
                "repository.read",
                new TaskProviderAccessRequest(
                        bindingId, new ProviderCapability("repository.read"),
                        "repository:crewscope"));

        TaskCredentialGrant used = service.authorizeUse("provider-token", access, 0);
        assertEquals(1, used.useCount());

        when(binding.status()).thenReturn(ProviderRegistrationStatus.DISABLED);
        assertThrows(RuntimeException.class, () ->
                service.authorizeUse("provider-token", access, 0));
    }

    @Test
    void rechecksCurrentConnectionGrantBeforeEveryAuthorizedUse() {
        TaskTokenJti jti = new TaskTokenJti(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        ProviderBindingId bindingId = ProviderBindingId.generate();
        ConnectionGrantId connectionGrantId = ConnectionGrantId.generate();
        TaskProviderAuthorization authorization = new TaskProviderAuthorization(
                bindingId, 4, Optional.of(connectionGrantId), Optional.of(7L),
                ProviderCapabilities.of("repository.read"),
                ProviderResourceScope.of("repository:crewscope"));
        TaskTokenGrantScope base = new TaskTokenGrantScope(
                fixture.workScope, fixture.taskId, fixture.executionId, 1,
                fixture.leaseId, fixture.environment, fixture.runtimeId, fixture.workerId,
                fixture.claimTokenHash, fixture.fencingToken, fixture.executionPrincipal,
                fixture.policyId, fixture.policyHash, fixture.overlayReference,
                Set.of("repository.read"), Set.of(authorization));
        TaskCredentialGrant grant = TaskCredentialGrant.reconstitute(
                io.crewscope.domain.task.TaskCredentialGrantId.generate(),
                jti.hash(), base, fixture.now,
                io.crewscope.domain.shared.time.UtcTimestamp.from(
                        fixture.now.value().plusSeconds(60)),
                TaskCredentialGrantStatus.ACTIVE, 0, Optional.empty(), Optional.empty(), 0,
                AuditMetadata.createdBy(fixture.actor.id(), fixture.now));
        when(codec.decode("delegated-token")).thenReturn(decoded(grant, jti));
        when(grants.findByJtiHash(fixture.organizationId, jti.hash()))
                .thenReturn(Optional.of(grant));
        ProviderBinding binding = mock(ProviderBinding.class);
        when(binding.status()).thenReturn(ProviderRegistrationStatus.ACTIVE);
        when(binding.version()).thenReturn(4L);
        when(binding.effectiveAccess()).thenReturn(new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"),
                ProviderResourceScope.of("repository:crewscope")));
        when(bindings.findById(fixture.organizationId, bindingId))
                .thenReturn(Optional.of(binding));
        ConnectionGrant connectionGrant = mock(ConnectionGrant.class);
        when(connectionGrant.status()).thenReturn(ConnectionGrantStatus.ACTIVE);
        when(connectionGrant.version()).thenReturn(7L);
        when(connectionGrant.validFrom()).thenReturn(fixture.now);
        when(connectionGrant.expiresAt()).thenReturn(Optional.of(
                io.crewscope.domain.shared.time.UtcTimestamp.from(
                        fixture.now.value().plusSeconds(120))));
        when(connectionGrant.grantedAccess()).thenReturn(new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"),
                ProviderResourceScope.of("repository:crewscope")));
        when(connectionGrants.findById(fixture.organizationId, connectionGrantId))
                .thenReturn(Optional.of(connectionGrant));
        TaskTokenAccessRequest access = TaskTokenAccessRequest.provider(
                "repository.read",
                new TaskProviderAccessRequest(
                        bindingId, new ProviderCapability("repository.read"),
                        "repository:crewscope"));

        service.authorizeUse("delegated-token", access, 0);
        when(connectionGrant.status()).thenReturn(ConnectionGrantStatus.REVOKED);

        assertThrows(RuntimeException.class, () ->
                service.authorizeUse("delegated-token", access, 0));
    }

    private DecodedTaskToken decoded(TaskCredentialGrant grant, TaskTokenJti jti) {
        return new DecodedTaskToken(
                TaskTokenClaims.AUDIENCE,
                grant.id(),
                jti,
                fixture.executor.id(),
                fixture.organizationId,
                fixture.environment,
                TaskTokenScopeFingerprint.compute(grant.scope()),
                grant.issuedAt(),
                grant.expiresAt());
    }
}
