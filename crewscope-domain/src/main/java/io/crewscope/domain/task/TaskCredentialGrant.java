package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Persisted, revocable authorization backing one short-lived Task Token. */
public final class TaskCredentialGrant {

    private final TaskCredentialGrantId id;
    private final TaskTokenJtiHash jtiHash;
    private final TaskTokenGrantScope scope;
    private final UtcTimestamp issuedAt;
    private final UtcTimestamp expiresAt;
    private final TaskCredentialGrantStatus status;
    private final long useCount;
    private final Optional<UtcTimestamp> lastUsedAt;
    private final Optional<TaskCredentialGrantTermination> termination;
    private final long version;
    private final AuditMetadata audit;

    private TaskCredentialGrant(
            TaskCredentialGrantId id,
            TaskTokenJtiHash jtiHash,
            TaskTokenGrantScope scope,
            UtcTimestamp issuedAt,
            UtcTimestamp expiresAt,
            TaskCredentialGrantStatus status,
            long useCount,
            Optional<UtcTimestamp> lastUsedAt,
            Optional<TaskCredentialGrantTermination> termination,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.jtiHash = Objects.requireNonNull(jtiHash, "jtiHash");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.status = Objects.requireNonNull(status, "status");
        if (useCount < 0) {
            throw new DomainValidationException(
                    "taskCredentialGrant.useCount", "must not be negative");
        }
        this.useCount = useCount;
        this.lastUsedAt = Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        this.termination = Objects.requireNonNull(termination, "termination");
        if (version < 0) {
            throw new DomainValidationException(
                    "taskCredentialGrant.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        validateShape();
    }

    /**
     * Issues a persistable grant and one-time plaintext claims from the same closed scope.
     *
     * <p>The JTI plaintext is never retained by the aggregate.
     */
    public static TaskCredentialIssuance issue(
            TaskCredentialGrantId id,
            TaskExecution execution,
            ExecutionLease lease,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            Set<String> requestedTools,
            Collection<TaskProviderGrantRequest> providerRequests,
            TaskTokenJti jti,
            UtcTimestamp expiresAt,
            Principal actor,
            UtcTimestamp issuedAt) {
        UtcTimestamp requiredIssuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        UtcTimestamp requiredExpiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        ExecutionLease requiredLease = Objects.requireNonNull(lease, "lease");
        TaskTokenGrantScope grantScope = TaskTokenGrantScope.issue(
                execution,
                requiredLease,
                policy,
                overlay,
                requestedTools,
                providerRequests,
                requiredIssuedAt);
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, grantScope.workItemScope(), "taskCredentialGrant.createdBy");
        TaskTokenClaims claims = new TaskTokenClaims(
                TaskTokenClaims.AUDIENCE,
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(jti, "jti"),
                grantScope,
                requiredIssuedAt,
                requiredExpiresAt);
        if (requiredExpiresAt.compareTo(requiredLease.expiresAt()) > 0) {
            throw new DomainValidationException(
                    "taskToken.expiresAt", "must not outlive the current ExecutionLease");
        }
        TaskCredentialGrant grant = new TaskCredentialGrant(
                id,
                jti.hash(),
                grantScope,
                requiredIssuedAt,
                requiredExpiresAt,
                TaskCredentialGrantStatus.ACTIVE,
                0,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredIssuedAt));
        return new TaskCredentialIssuance(grant, claims);
    }

    /** Reconstitutes one persisted grant while enforcing lifecycle and timeline invariants. */
    public static TaskCredentialGrant reconstitute(
            TaskCredentialGrantId id,
            TaskTokenJtiHash jtiHash,
            TaskTokenGrantScope scope,
            UtcTimestamp issuedAt,
            UtcTimestamp expiresAt,
            TaskCredentialGrantStatus status,
            long useCount,
            Optional<UtcTimestamp> lastUsedAt,
            Optional<TaskCredentialGrantTermination> termination,
            long version,
            AuditMetadata audit) {
        return new TaskCredentialGrant(
                id, jtiHash, scope, issuedAt, expiresAt, status, useCount, lastUsedAt,
                termination, version, audit);
    }

    /** Authorizes and records one exact Tool or Provider resource use. */
    public TaskCredentialGrant use(
            TaskTokenClaims presentedClaims,
            ExecutionLease activeLease,
            TaskTokenAccessRequest request,
            long expectedVersion,
            UtcTimestamp authoritativeNow) {
        requireExpectedVersion(expectedVersion);
        requireActive();
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        TaskTokenClaims claims = requireMatchingClaims(presentedClaims);
        claims.requireValidAt(now);
        scope.requireActiveLease(activeLease, now);
        scope.requireAllowed(request);
        return copy(
                TaskCredentialGrantStatus.ACTIVE,
                useCount + 1,
                Optional.of(now),
                Optional.empty(),
                version + 1,
                audit.modifiedBy(scope.executionPrincipal().principalId(), now));
    }

    /** Verifies the signed claims and current Lease without consuming a Tool authorization. */
    public void authenticate(
            TaskTokenClaims presentedClaims,
            ExecutionLease activeLease,
            UtcTimestamp authoritativeNow) {
        requireActive();
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        requireMatchingClaims(presentedClaims).requireValidAt(now);
        scope.requireActiveLease(activeLease, now);
    }

    /** Revokes a live grant before expiry; the terminal fact is immutable. */
    public TaskCredentialGrant revoke(
            long expectedVersion,
            Principal actor,
            String reason,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireActive();
        UtcTimestamp requiredTime = requireNotBeforeIssuance(occurredAt);
        if (requiredTime.compareTo(expiresAt) >= 0) {
            throw new DomainValidationException(
                    "taskCredentialGrant.termination.status",
                    "must use EXPIRED at or after the expiry boundary");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope.workItemScope(), "taskCredentialGrant.terminatedBy");
        TaskCredentialGrantTermination terminal = new TaskCredentialGrantTermination(
                TaskCredentialGrantStatus.REVOKED, actorId, requiredTime, reason);
        return copy(
                TaskCredentialGrantStatus.REVOKED,
                useCount,
                lastUsedAt,
                Optional.of(terminal),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    /** Commits expiry at or after the exact authoritative deadline. */
    public TaskCredentialGrant expire(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireActive();
        UtcTimestamp requiredTime = requireNotBeforeIssuance(occurredAt);
        if (requiredTime.compareTo(expiresAt) < 0) {
            throw new DomainValidationException(
                    "taskCredentialGrant.expiresAt", "must have elapsed before expiry is committed");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, scope.workItemScope(), "taskCredentialGrant.terminatedBy");
        TaskCredentialGrantTermination terminal = new TaskCredentialGrantTermination(
                TaskCredentialGrantStatus.EXPIRED,
                actorId,
                requiredTime,
                "TASK_TOKEN_EXPIRED");
        return copy(
                TaskCredentialGrantStatus.EXPIRED,
                useCount,
                lastUsedAt,
                Optional.of(terminal),
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    public boolean isExpired(UtcTimestamp authoritativeNow) {
        UtcTimestamp now = requireNotBeforeIssuance(authoritativeNow);
        return now.compareTo(expiresAt) >= 0;
    }

    private TaskTokenClaims requireMatchingClaims(TaskTokenClaims claims) {
        TaskTokenClaims required = Objects.requireNonNull(claims, "presentedClaims");
        boolean matches = TaskTokenClaims.AUDIENCE.equals(required.audience())
                && id.equals(required.grantId())
                && jtiHash.equals(required.jti().hash())
                && scope.equals(required.scope())
                && issuedAt.equals(required.issuedAt())
                && expiresAt.equals(required.expiresAt());
        if (!matches) {
            throw new DomainValidationException(
                    "taskCredentialGrant.claims", "must exactly match the persisted grant");
        }
        return required;
    }

    private UtcTimestamp requireNotBeforeIssuance(UtcTimestamp value) {
        UtcTimestamp required = Objects.requireNonNull(value, "occurredAt");
        if (required.compareTo(issuedAt) < 0) {
            throw new DomainValidationException(
                    "taskCredentialGrant.timeline", "must not be before issuance");
        }
        return required;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "TaskCredentialGrant", id, expectedVersion, version);
        }
    }

    private void requireActive() {
        if (status != TaskCredentialGrantStatus.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "TaskCredentialGrant", id, status, status);
        }
    }

    private void validateShape() {
        long expectedVersion = useCount + (status == TaskCredentialGrantStatus.ACTIVE ? 0 : 1);
        if (version != expectedVersion) {
            throw new DomainValidationException(
                    "taskCredentialGrant.version",
                    "must equal committed uses plus the optional terminal transition");
        }
        Duration lifetime = Duration.between(issuedAt.value(), expiresAt.value());
        if (lifetime.compareTo(TaskTokenClaims.MIN_LIFETIME) < 0
                || lifetime.compareTo(TaskTokenClaims.MAX_LIFETIME) > 0) {
            throw new DomainValidationException(
                    "taskCredentialGrant.timeline", "must use the bounded Task Token lifetime");
        }
        if ((useCount == 0) == lastUsedAt.isPresent()) {
            throw new DomainValidationException(
                    "taskCredentialGrant.lastUsedAt",
                    "must be present exactly when the grant has been used");
        }
        lastUsedAt.ifPresent(value -> {
            if (value.compareTo(issuedAt) < 0 || value.compareTo(expiresAt) >= 0) {
                throw new DomainValidationException(
                        "taskCredentialGrant.lastUsedAt", "must fall within the token lifetime");
            }
        });
        if ((status == TaskCredentialGrantStatus.ACTIVE) == termination.isPresent()) {
            throw new DomainValidationException(
                    "taskCredentialGrant.termination",
                    "must be absent for ACTIVE and present for a terminal status");
        }
        termination.ifPresent(value -> {
            if (value.status() != status || value.terminatedAt().compareTo(issuedAt) < 0) {
                throw new DomainValidationException(
                        "taskCredentialGrant.termination", "must match status and issuance timeline");
            }
            if (status == TaskCredentialGrantStatus.EXPIRED
                    && value.terminatedAt().compareTo(expiresAt) < 0) {
                throw new DomainValidationException(
                        "taskCredentialGrant.termination", "EXPIRED must be committed after expiry");
            }
            if (status == TaskCredentialGrantStatus.REVOKED
                    && value.terminatedAt().compareTo(expiresAt) >= 0) {
                throw new DomainValidationException(
                        "taskCredentialGrant.termination", "REVOKED must be committed before expiry");
            }
            if (lastUsedAt.filter(lastUse -> value.terminatedAt().compareTo(lastUse) < 0)
                    .isPresent()) {
                throw new DomainValidationException(
                        "taskCredentialGrant.termination",
                        "must not be before the latest authorized use");
            }
        });
        if (!audit.createdAt().equals(issuedAt)
                || audit.updatedAt().compareTo(issuedAt) < 0) {
            throw new DomainValidationException(
                    "taskCredentialGrant.audit", "must begin at the exact issuance time");
        }
        UtcTimestamp expectedUpdatedAt = termination
                .map(TaskCredentialGrantTermination::terminatedAt)
                .or(() -> lastUsedAt)
                .orElse(issuedAt);
        if (!audit.updatedAt().equals(expectedUpdatedAt)) {
            throw new DomainValidationException(
                    "taskCredentialGrant.audit", "must end at the latest lifecycle fact");
        }
    }

    private TaskCredentialGrant copy(
            TaskCredentialGrantStatus targetStatus,
            long targetUseCount,
            Optional<UtcTimestamp> targetLastUsedAt,
            Optional<TaskCredentialGrantTermination> targetTermination,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new TaskCredentialGrant(
                id, jtiHash, scope, issuedAt, expiresAt, targetStatus, targetUseCount,
                targetLastUsedAt, targetTermination, targetVersion, targetAudit);
    }

    public TaskCredentialGrantId id() { return id; }
    public TaskTokenJtiHash jtiHash() { return jtiHash; }
    public TaskTokenGrantScope scope() { return scope; }
    public UtcTimestamp issuedAt() { return issuedAt; }
    public UtcTimestamp expiresAt() { return expiresAt; }
    public TaskCredentialGrantStatus status() { return status; }
    public long useCount() { return useCount; }
    public Optional<UtcTimestamp> lastUsedAt() { return lastUsedAt; }
    public Optional<TaskCredentialGrantTermination> termination() { return termination; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }

    @Override
    public String toString() {
        return "TaskCredentialGrant[id=" + id
                + ", scope=" + scope
                + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt
                + ", status=" + status
                + ", useCount=" + useCount
                + ", jtiHash=[REDACTED]]";
    }
}
