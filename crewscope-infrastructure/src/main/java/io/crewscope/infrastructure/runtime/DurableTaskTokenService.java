package io.crewscope.infrastructure.runtime;

import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.task.DecodedTaskToken;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.TaskCredentialGrantRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskTokenCodec;
import io.crewscope.application.task.TaskTokenExecutionContext;
import io.crewscope.application.task.TaskTokenIssueCommand;
import io.crewscope.application.task.TaskTokenIssueResult;
import io.crewscope.application.task.TaskTokenJtiGenerator;
import io.crewscope.application.task.TaskTokenRevokeCommand;
import io.crewscope.application.task.TaskTokenRotateCommand;
import io.crewscope.application.task.TaskTokenScopeFingerprint;
import io.crewscope.application.task.TaskTokenService;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskCredentialIssuance;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskProviderAccessRequest;
import io.crewscope.domain.task.TaskProviderAuthorization;
import io.crewscope.domain.task.TaskTokenAccessRequest;
import io.crewscope.domain.task.TaskTokenClaims;
import io.crewscope.domain.task.TaskTokenGrantScope;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Transactional Task Token authority backed by current Lease and revocable Grant facts. */
public final class DurableTaskTokenService implements TaskTokenService {

    private final TaskExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final PolicySnapshotRepository policyRepository;
    private final SafetyEnforcementOverlayRepository overlayRepository;
    private final TaskCredentialGrantRepository grantRepository;
    private final TaskTokenCurrentAuthorization currentAuthorization;
    private final ProviderBindingRepository bindingRepository;
    private final ConnectionGrantRepository connectionGrantRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final TaskTokenJtiGenerator jtiGenerator;
    private final TaskTokenCodec codec;
    private final TaskTokenServiceSpec spec;

    public DurableTaskTokenService(
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            TaskCredentialGrantRepository grantRepository,
            TaskTokenCurrentAuthorization currentAuthorization,
            ProviderBindingRepository bindingRepository,
            ConnectionGrantRepository connectionGrantRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            TaskTokenJtiGenerator jtiGenerator,
            TaskTokenCodec codec,
            TaskTokenServiceSpec spec) {
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.overlayRepository = Objects.requireNonNull(overlayRepository, "overlayRepository");
        this.grantRepository = Objects.requireNonNull(grantRepository, "grantRepository");
        this.currentAuthorization = Objects.requireNonNull(
                currentAuthorization, "currentAuthorization");
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.connectionGrantRepository = Objects.requireNonNull(
                connectionGrantRepository, "connectionGrantRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.jtiGenerator = Objects.requireNonNull(jtiGenerator, "jtiGenerator");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    @Override
    public TaskTokenIssueResult issue(TaskTokenIssueCommand command) {
        return transactionExecutor.required(() -> issueTransaction(
                Objects.requireNonNull(command, "command")));
    }

    @Override
    public TaskTokenIssueResult rotate(TaskTokenRotateCommand command) {
        return transactionExecutor.required(() -> {
            TaskTokenRotateCommand required = Objects.requireNonNull(command, "command");
            UtcTimestamp now = timeProvider.now();
            LoadedToken current = load(required.currentToken(), now);
            TaskTokenIssueCommand replacementCommand = new TaskTokenIssueCommand(
                    current.grant().scope().taskExecutionId(),
                    current.grant().scope().executionLeaseId(),
                    required.allowedTools(),
                    required.providerRequests(),
                    required.lifetime());
            TaskCredentialIssuance replacement = createIssuance(replacementCommand, now);
            requireNarrower(current.grant().scope(), replacement.grant().scope());
            TaskCredentialGrant revoked = current.grant().revoke(
                    required.expectedGrantVersion(), spec.actor(), "TASK_TOKEN_ROTATED", now);
            String encoded = codec.encode(replacement.claims());
            TaskCredentialGrant persisted = grantRepository.rotate(revoked, replacement.grant());
            return result(encoded, persisted);
        });
    }

    @Override
    public TaskTokenExecutionContext authenticate(String token) {
        return transactionExecutor.required(() -> {
            UtcTimestamp now = timeProvider.now();
            return context(load(requireToken(token), now).grant());
        });
    }

    @Override
    public TaskCredentialGrant authorizeUse(
            String token, TaskTokenAccessRequest request, long expectedGrantVersion) {
        return transactionExecutor.required(() -> {
            UtcTimestamp now = timeProvider.now();
            LoadedToken loaded = load(requireToken(token), now);
            TaskTokenAccessRequest required = Objects.requireNonNull(request, "request");
            requireCurrentProviderAuthorization(loaded.grant(), required, now);
            TaskCredentialGrant used = loaded.grant().use(
                    loaded.claims(), loaded.lease(), required, expectedGrantVersion, now);
            return grantRepository.recordUse(used);
        });
    }

    @Override
    public TaskCredentialGrant revoke(TaskTokenRevokeCommand command) {
        return transactionExecutor.required(() -> {
            TaskTokenRevokeCommand required = Objects.requireNonNull(command, "command");
            UtcTimestamp now = timeProvider.now();
            LoadedToken loaded = loadSignedGrant(required.token());
            TaskCredentialGrant terminated = loaded.grant().isExpired(now)
                    ? loaded.grant().expire(required.expectedGrantVersion(), spec.actor(), now)
                    : loaded.grant().revoke(
                            required.expectedGrantVersion(), spec.actor(), required.reason(), now);
            return grantRepository.terminate(terminated);
        });
    }

    private TaskTokenIssueResult issueTransaction(TaskTokenIssueCommand command) {
        UtcTimestamp now = timeProvider.now();
        TaskCredentialIssuance issuance = createIssuance(command, now);
        String token = codec.encode(issuance.claims());
        TaskCredentialGrant persisted = grantRepository.create(issuance.grant());
        return result(token, persisted);
    }

    private TaskCredentialIssuance createIssuance(
            TaskTokenIssueCommand command, UtcTimestamp now) {
        requireLifetime(command.lifetime());
        TaskExecution execution = executionRepository.findById(
                        spec.organizationId(), command.taskExecutionId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", command.taskExecutionId()));
        ExecutionLease lease = leaseRepository.findById(
                        spec.organizationId(), spec.environment(), command.executionLeaseId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ExecutionLease", command.executionLeaseId()));
        var planning = execution.planningContext().orElseThrow(() -> new DomainValidationException(
                "taskToken.policySnapshotId", "requires a current planning context"));
        PolicySnapshot policy = policyRepository.findById(
                        spec.organizationId(), planning.policySnapshotId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "PolicySnapshot", planning.policySnapshotId()));
        SafetyEnforcementOverlay overlay = overlayRepository.findByIdAndVersion(
                        spec.organizationId(),
                        planning.safetyOverlay().id(),
                        planning.safetyOverlay().version())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "SafetyEnforcementOverlay", planning.safetyOverlay().id()));
        UtcTimestamp requestedExpiry = UtcTimestamp.from(now.value().plus(command.lifetime()));
        UtcTimestamp expiresAt = requestedExpiry.compareTo(lease.expiresAt()) <= 0
                ? requestedExpiry : lease.expiresAt();
        TaskCredentialIssuance issuance = TaskCredentialGrant.issue(
                TaskCredentialGrantId.generate(), execution, lease, policy, overlay,
                command.allowedTools(), command.providerRequests(), jtiGenerator.generate(),
                expiresAt, spec.actor(), now);
        currentAuthorization.requireCurrent(issuance.grant());
        return issuance;
    }

    private LoadedToken load(String token, UtcTimestamp now) {
        LoadedToken loaded = loadSignedGrant(token);
        loaded.grant().authenticate(loaded.claims(), loaded.lease(), now);
        currentAuthorization.requireCurrent(loaded.grant());
        return loaded;
    }

    private LoadedToken loadSignedGrant(String token) {
        DecodedTaskToken decoded = codec.decode(requireToken(token));
        if (!TaskTokenClaims.AUDIENCE.equals(decoded.audience())) {
            throw invalidToken();
        }
        if (!spec.organizationId().equals(decoded.organizationId())
                || !spec.environment().equals(decoded.environment())) {
            throw invalidToken();
        }
        TaskCredentialGrant grant = grantRepository.findByJtiHash(
                        spec.organizationId(), decoded.jti().hash())
                .orElseThrow(DurableTaskTokenService::invalidToken);
        if (!grant.id().equals(decoded.grantId())
                || !grant.issuedAt().equals(decoded.issuedAt())
                || !grant.expiresAt().equals(decoded.expiresAt())
                || !grant.scope().executionPrincipal().principalId().equals(decoded.subject())
                || !TaskTokenScopeFingerprint.compute(grant.scope())
                        .equals(decoded.scopeFingerprint())) {
            throw invalidToken();
        }
        TaskTokenClaims claims = new TaskTokenClaims(
                decoded.audience(), grant.id(), decoded.jti(), grant.scope(),
                decoded.issuedAt(), decoded.expiresAt());
        ExecutionLease lease = leaseRepository.findById(
                        spec.organizationId(), spec.environment(), grant.scope().executionLeaseId())
                .orElseThrow(DurableTaskTokenService::invalidToken);
        return new LoadedToken(grant, claims, lease);
    }

    private void requireCurrentProviderAuthorization(
            TaskCredentialGrant grant, TaskTokenAccessRequest request, UtcTimestamp now) {
        TaskProviderAccessRequest access = request.providerAccess().orElse(null);
        if (access == null) {
            return;
        }
        TaskProviderAuthorization pinned = grant.scope().providerAuthorizations().stream()
                .filter(value -> value.bindingId().equals(access.bindingId()))
                .findFirst().orElseThrow(DurableTaskTokenService::invalidToken);
        ProviderBinding binding = bindingRepository.findById(
                        spec.organizationId(), access.bindingId())
                .orElseThrow(DurableTaskTokenService::invalidToken);
        boolean bindingCurrent = binding.status() == ProviderRegistrationStatus.ACTIVE
                && binding.version() == pinned.bindingVersion()
                && binding.effectiveAccess().capabilities().values().contains(access.capability())
                && (binding.effectiveAccess().resources().unrestricted()
                        || binding.effectiveAccess().resources().resources().contains(access.resource()));
        if (!bindingCurrent) {
            throw invalidToken();
        }
        pinned.connectionGrantId().ifPresent(grantId -> {
            ConnectionGrant current = connectionGrantRepository.findById(
                            spec.organizationId(), grantId)
                    .orElseThrow(DurableTaskTokenService::invalidToken);
            boolean currentGrant = current.status() == ConnectionGrantStatus.ACTIVE
                    && current.version() == pinned.connectionGrantVersion().orElseThrow()
                    && current.validFrom().compareTo(now) <= 0
                    && current.expiresAt().map(value -> value.compareTo(now) > 0).orElse(true)
                    && current.grantedAccess().capabilities().values().contains(access.capability())
                    && (current.grantedAccess().resources().unrestricted()
                            || current.grantedAccess().resources().resources().contains(access.resource()));
            if (!currentGrant) {
                throw invalidToken();
            }
        });
    }

    private static void requireNarrower(TaskTokenGrantScope current, TaskTokenGrantScope replacement) {
        if (!replacement.allowedTools().stream().allMatch(current.allowedTools()::contains)) {
            throw new DomainValidationException(
                    "taskToken.rotation.allowedTools", "must not broaden the current token scope");
        }
        Map<io.crewscope.domain.provider.ProviderBindingId, TaskProviderAuthorization> existing =
                current.providerAuthorizations().stream()
                .collect(Collectors.toMap(TaskProviderAuthorization::bindingId, Function.identity()));
        for (TaskProviderAuthorization candidate : replacement.providerAuthorizations()) {
            TaskProviderAuthorization parent = existing.get(candidate.bindingId());
            if (parent == null
                    || !parent.connectionGrantId().equals(candidate.connectionGrantId())
                    || !parent.capabilities().includes(candidate.capabilities())
                    || (!parent.resources().unrestricted()
                            && !parent.resources().resources().containsAll(
                                    candidate.resources().resources()))) {
                throw new DomainValidationException(
                        "taskToken.rotation.providerAuthorizations",
                        "must not broaden or replace the current Provider scope");
            }
        }
    }

    private static void requireLifetime(Duration lifetime) {
        Duration required = Objects.requireNonNull(lifetime, "lifetime");
        if (required.compareTo(TaskTokenClaims.MIN_LIFETIME) < 0
                || required.compareTo(TaskTokenClaims.MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("Task Token lifetime must be between 5s and 15m");
        }
    }

    private static TaskTokenIssueResult result(String token, TaskCredentialGrant grant) {
        return new TaskTokenIssueResult(token, grant, context(grant));
    }

    private static TaskTokenExecutionContext context(TaskCredentialGrant grant) {
        return new TaskTokenExecutionContext(
                grant.id(), grant.version(), grant.scope(), grant.expiresAt());
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

    private record LoadedToken(
            TaskCredentialGrant grant, TaskTokenClaims claims, ExecutionLease lease) {}
}
