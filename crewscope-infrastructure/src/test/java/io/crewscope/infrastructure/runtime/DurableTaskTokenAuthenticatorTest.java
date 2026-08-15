package io.crewscope.infrastructure.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.DecodedTaskToken;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskTokenCodec;
import io.crewscope.application.task.TaskTokenScopeFingerprint;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskTokenClaims;
import io.crewscope.domain.task.TaskTokenJti;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Current Grant, Lease, execution, Principal and exact expiry authentication tests. */
class DurableTaskTokenAuthenticatorTest {

    private final TaskCredentialGrantRepository grants = mock(TaskCredentialGrantRepository.class);
    private final ExecutionLeaseRepository leases = mock(ExecutionLeaseRepository.class);
    private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    private final PrincipalRepository principals = mock(PrincipalRepository.class);
    private final TaskTokenCodec codec = mock(TaskTokenCodec.class);
    private final AtomicReference<io.crewscope.domain.shared.time.UtcTimestamp> now =
            new AtomicReference<>();
    private TaskTokenRuntimeFixture fixture;
    private TaskTokenJti jti;
    private TaskCredentialGrant grant;
    private DurableTaskTokenAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        fixture = new TaskTokenRuntimeFixture();
        now.set(fixture.now);
        jti = new TaskTokenJti("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");
        grant = io.crewscope.domain.task.TaskCredentialGrant.issue(
                TaskCredentialGrantId.generate(), fixture.execution, fixture.lease,
                fixture.policy, fixture.overlay, Set.of("repository.read"), List.of(), jti,
                io.crewscope.domain.shared.time.UtcTimestamp.from(
                        fixture.now.value().plus(Duration.ofSeconds(60))),
                fixture.actor, fixture.now).grant();
        when(codec.decode("signed-token")).thenReturn(decoded(
                TaskTokenScopeFingerprint.compute(grant.scope()),
                fixture.environment,
                fixture.actor.id()));
        when(grants.findByJtiHash(fixture.organizationId, jti.hash()))
                .thenReturn(Optional.of(grant));
        when(leases.findById(fixture.organizationId, fixture.environment, fixture.leaseId))
                .thenReturn(Optional.of(fixture.lease));
        when(executions.findById(fixture.organizationId, fixture.executionId))
                .thenReturn(Optional.of(fixture.execution));
        when(principals.findById(fixture.organizationId, fixture.actor.id()))
                .thenReturn(Optional.of(fixture.actor));
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        AuthoritativeTimeProvider timeProvider = now::get;
        authenticator = new DurableTaskTokenAuthenticator(
                grants, leases, executions, principals, transactions, timeProvider, codec);
    }

    @Test
    void authenticatesOnlyTheCurrentClosedFacts() {
        var context = authenticator.authenticate("signed-token");

        assertEquals(grant.id(), context.grantId());
        assertEquals(fixture.executionId, context.scope().taskExecutionId());
        assertEquals(fixture.workerId, context.scope().workerId());
    }

    @Test
    void revocationIsEffectiveOnTheNextRequest() {
        var revokedAt = io.crewscope.domain.shared.time.UtcTimestamp.from(
                fixture.now.value().plusSeconds(1));
        TaskCredentialGrant revoked = grant.revoke(
                0, fixture.actor, "ACCESS_REVOKED", revokedAt);
        when(grants.findByJtiHash(fixture.organizationId, jti.hash()))
                .thenReturn(Optional.of(revoked));
        now.set(revokedAt);

        assertThrows(RuntimeException.class, () -> authenticator.authenticate("signed-token"));
    }

    @Test
    void rejectsAtTheExactExpiryBoundary() {
        now.set(grant.expiresAt());

        assertThrows(RuntimeException.class, () -> authenticator.authenticate("signed-token"));
    }

    @Test
    void rejectsStaleWorkerLeaseAndDisabledExecutionPrincipal() {
        ExecutionLease stale = mock(ExecutionLease.class);
        when(stale.id()).thenReturn(fixture.leaseId);
        when(stale.organizationId()).thenReturn(fixture.organizationId);
        when(stale.environment()).thenReturn(fixture.environment);
        when(stale.taskExecutionId()).thenReturn(fixture.executionId);
        when(stale.attempt()).thenReturn(1);
        when(stale.runtimeId()).thenReturn(fixture.runtimeId);
        when(stale.workerId()).thenReturn(io.crewscope.domain.runtime.RuntimeWorkerId.generate());
        when(stale.claimTokenHash()).thenReturn(fixture.claimTokenHash);
        when(stale.fencingToken()).thenReturn(fixture.fencingToken);
        when(stale.isActiveAt(fixture.now)).thenReturn(true);
        when(leases.findById(fixture.organizationId, fixture.environment, fixture.leaseId))
                .thenReturn(Optional.of(stale));
        assertThrows(RuntimeException.class, () -> authenticator.authenticate("signed-token"));

        when(leases.findById(fixture.organizationId, fixture.environment, fixture.leaseId))
                .thenReturn(Optional.of(fixture.lease));
        var suspended = fixture.actor.transitionTo(
                PrincipalStatus.SUSPENDED,
                io.crewscope.domain.shared.time.UtcTimestamp.from(
                        fixture.now.value().plusSeconds(1)));
        when(principals.findById(fixture.organizationId, fixture.actor.id()))
                .thenReturn(Optional.of(suspended));
        assertThrows(RuntimeException.class, () -> authenticator.authenticate("signed-token"));
    }

    @Test
    void rejectsSignedEnvelopeScopeOrEnvironmentSubstitution() {
        when(codec.decode("fingerprint-token")).thenReturn(decoded(
                "0".repeat(64), fixture.environment, fixture.actor.id()));
        when(codec.decode("environment-token")).thenReturn(decoded(
                TaskTokenScopeFingerprint.compute(grant.scope()),
                new io.crewscope.domain.runtime.RuntimeEnvironment("other"),
                fixture.actor.id()));

        assertThrows(RuntimeException.class, () -> authenticator.authenticate("fingerprint-token"));
        assertThrows(RuntimeException.class, () -> authenticator.authenticate("environment-token"));
    }

    private DecodedTaskToken decoded(
            String fingerprint,
            io.crewscope.domain.runtime.RuntimeEnvironment environment,
            io.crewscope.domain.shared.id.PrincipalId subject) {
        return new DecodedTaskToken(
                TaskTokenClaims.AUDIENCE, grant.id(), jti, subject,
                fixture.organizationId, environment, fingerprint,
                grant.issuedAt(), grant.expiresAt());
    }
}
