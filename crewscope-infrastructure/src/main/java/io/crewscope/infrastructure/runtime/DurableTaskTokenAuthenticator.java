package io.crewscope.infrastructure.runtime;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.DecodedTaskToken;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskTokenAuthenticator;
import io.crewscope.application.task.TaskTokenCodec;
import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.application.task.TaskTokenScopeFingerprint;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskTokenClaims;
import java.util.Objects;

/** Profile-neutral signature, Grant, Lease, execution and Principal authentication boundary. */
public final class DurableTaskTokenAuthenticator implements TaskTokenAuthenticator {

    private final TaskCredentialGrantRepository grantRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final TaskExecutionRepository executionRepository;
    private final PrincipalRepository principalRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final TaskTokenCodec codec;

    public DurableTaskTokenAuthenticator(
            TaskCredentialGrantRepository grantRepository,
            ExecutionLeaseRepository leaseRepository,
            TaskExecutionRepository executionRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            TaskTokenCodec codec) {
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public TaskTokenExecutionContext authenticate(String token) {
        return transactionExecutor.required(() -> authenticateTransaction(token));
    }

    private TaskTokenExecutionContext authenticateTransaction(String token) {
        DecodedTaskToken decoded = codec.decode(requireToken(token));
        if (!TaskTokenClaims.AUDIENCE.equals(decoded.audience())) {
            throw invalidToken();
        }
        TaskCredentialGrant grant = grantRepository.findByJtiHash(
                        decoded.organizationId(), decoded.jti().hash())
                .orElseThrow(DurableTaskTokenAuthenticator::invalidToken);
        if (!grant.id().equals(decoded.grantId())
                || !grant.issuedAt().equals(decoded.issuedAt())
                || !grant.expiresAt().equals(decoded.expiresAt())
                || !grant.scope().workItemScope().organizationId().equals(decoded.organizationId())
                || !grant.scope().environment().equals(decoded.environment())
                || !grant.scope().executionPrincipal().principalId().equals(decoded.subject())
                || !TaskTokenScopeFingerprint.compute(grant.scope())
                        .equals(decoded.scopeFingerprint())) {
            throw invalidToken();
        }
        var lease = leaseRepository.findById(
                        decoded.organizationId(), decoded.environment(),
                        grant.scope().executionLeaseId())
                .orElseThrow(DurableTaskTokenAuthenticator::invalidToken);
        UtcTimestamp now = timeProvider.now();
        TaskTokenClaims claims = new TaskTokenClaims(
                decoded.audience(), grant.id(), decoded.jti(), grant.scope(),
                decoded.issuedAt(), decoded.expiresAt());
        grant.authenticate(claims, lease, now);
        requireCurrentExecution(grant);
        return new TaskTokenExecutionContext(
                grant.id(), grant.version(), grant.scope(), grant.expiresAt());
    }

    private void requireCurrentExecution(TaskCredentialGrant grant) {
        var scope = grant.scope();
        TaskExecution execution = executionRepository.findById(
                        scope.workItemScope().organizationId(), scope.taskExecutionId())
                .orElseThrow(DurableTaskTokenAuthenticator::invalidToken);
        var planning = execution.planningContext().orElseThrow(
                DurableTaskTokenAuthenticator::invalidToken);
        boolean current = execution.scope().equals(scope.workItemScope())
                && execution.taskId().equals(scope.taskId())
                && execution.attempt() == scope.attempt()
                && execution.lastFencingToken().filter(scope.fencingToken()::equals).isPresent()
                && planning.executionPrincipal().equals(scope.executionPrincipal())
                && planning.policySnapshotId().equals(scope.policySnapshotId())
                && planning.policySnapshotHash().equals(scope.policySnapshotHash())
                && planning.safetyOverlay().equals(scope.safetyOverlay());
        var principal = principalRepository.findById(
                        scope.workItemScope().organizationId(),
                        scope.executionPrincipal().principalId())
                .orElseThrow(DurableTaskTokenAuthenticator::invalidToken);
        if (!current
                || !principal.canAct()
                || !principal.scope().organizationId().equals(
                        scope.workItemScope().organizationId())) {
            throw invalidToken();
        }
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 16384) {
            throw invalidToken();
        }
        return token;
    }

    private static DomainValidationException invalidToken() {
        return new DomainValidationException("taskToken", "is invalid or no longer authorized");
    }
}
