package io.crewscope.domain.task;

import io.crewscope.domain.runtime.ExecutionRuntime;
import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.runtime.RuntimeWorker;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Exclusive, expiring ownership of one complete TaskExecution attempt.
 *
 * <p>Steps inherit this ownership and never create or renew their own Lease in the MVP.
 */
public final class ExecutionLease {

    public static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(5);
    public static final Duration MAX_PREPARE_LEASE_DURATION = Duration.ofMinutes(15);
    public static final Duration MAX_RUN_LEASE_DURATION = Duration.ofMinutes(10);

    private final ExecutionLeaseId id;
    private final OrganizationId organizationId;
    private final RuntimeEnvironment environment;
    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final ExecutionRuntimeId runtimeId;
    private final RuntimeWorkerId workerId;
    private final ClaimTokenHash claimTokenHash;
    private final FencingToken fencingToken;
    private final ExecutionLeasePhase phase;
    private final UtcTimestamp acquiredAt;
    private final UtcTimestamp lastHeartbeatAt;
    private final UtcTimestamp expiresAt;
    private final Optional<ExecutionLeaseRelease> release;
    private final long version;

    private ExecutionLease(
            ExecutionLeaseId id,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionRuntimeId runtimeId,
            RuntimeWorkerId workerId,
            ClaimTokenHash claimTokenHash,
            FencingToken fencingToken,
            ExecutionLeasePhase phase,
            UtcTimestamp acquiredAt,
            UtcTimestamp lastHeartbeatAt,
            UtcTimestamp expiresAt,
            Optional<ExecutionLeaseRelease> release,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || attempt > TaskExecution.MAX_SUPPORTED_ATTEMPTS) {
            throw new DomainValidationException(
                    "executionLease.attempt", "must be within the supported attempt range");
        }
        this.attempt = attempt;
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.claimTokenHash = Objects.requireNonNull(claimTokenHash, "claimTokenHash");
        this.fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt");
        this.lastHeartbeatAt = Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.release = Objects.requireNonNull(release, "release");
        if (version < 0) {
            throw new DomainValidationException("executionLease.version", "must not be negative");
        }
        this.version = version;
        validateTimeline();
    }

    /** Creates a PREPARE Lease after the same transaction commits the READY attempt as CLAIMED. */
    public static ExecutionLease acquire(
            ExecutionLeaseId id,
            TaskExecution claimedExecution,
            ExecutionRuntime runtime,
            RuntimeWorker worker,
            RuntimeCapabilities requiredCapabilities,
            Duration workerHeartbeatTimeout,
            ClaimToken claimToken,
            UtcTimestamp acquiredAt,
            UtcTimestamp expiresAt) {
        TaskExecution execution = Objects.requireNonNull(claimedExecution, "claimedExecution");
        ExecutionRuntime selectedRuntime = Objects.requireNonNull(runtime, "runtime");
        RuntimeWorker selectedWorker = Objects.requireNonNull(worker, "worker");
        UtcTimestamp requiredAcquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt");
        if (execution.status() != TaskExecutionStatus.CLAIMED) {
            throw new DomainValidationException(
                    "executionLease.taskExecutionId", "must reference a CLAIMED TaskExecution");
        }
        FencingToken currentFencingToken = execution.lastFencingToken().orElseThrow(() ->
                new DomainValidationException(
                        "executionLease.fencingToken",
                        "must use the TaskExecution committed ownership epoch"));
        if (!execution.scope().organizationId().equals(selectedRuntime.organizationId())) {
            throw new DomainValidationException(
                    "executionLease.organizationId", "must match TaskExecution and Runtime");
        }
        if (!selectedWorker.canClaim(
                selectedRuntime,
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"),
                requiredAcquiredAt,
                workerHeartbeatTimeout)) {
            throw new DomainValidationException(
                    "executionLease.workerId", "must reference an active claimable Worker");
        }
        requireDuration(
                requiredAcquiredAt,
                expiresAt,
                ExecutionLeasePhase.PREPARE,
                "executionLease.expiresAt");
        ClaimToken requiredClaimToken = Objects.requireNonNull(claimToken, "claimToken");
        return new ExecutionLease(
                id,
                execution.scope().organizationId(),
                selectedRuntime.environment(),
                execution.id(),
                execution.attempt(),
                selectedRuntime.id(),
                selectedWorker.id(),
                requiredClaimToken.hash(),
                currentFencingToken,
                ExecutionLeasePhase.PREPARE,
                requiredAcquiredAt,
                requiredAcquiredAt,
                expiresAt,
                Optional.empty(),
                0);
    }

    public static ExecutionLease reconstitute(
            ExecutionLeaseId id,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            TaskExecutionId taskExecutionId,
            int attempt,
            ExecutionRuntimeId runtimeId,
            RuntimeWorkerId workerId,
            ClaimTokenHash claimTokenHash,
            FencingToken fencingToken,
            ExecutionLeasePhase phase,
            UtcTimestamp acquiredAt,
            UtcTimestamp lastHeartbeatAt,
            UtcTimestamp expiresAt,
            Optional<ExecutionLeaseRelease> release,
            long version) {
        return new ExecutionLease(
                id, organizationId, environment, taskExecutionId, attempt, runtimeId, workerId,
                claimTokenHash, fencingToken, phase, acquiredAt, lastHeartbeatAt, expiresAt,
                release, version);
    }

    /** Switches the short preparation lease to the renewable run phase. */
    public ExecutionLease beginRun(
            TaskExecution runningExecution,
            LeaseOwnership ownership,
            long expectedVersion,
            UtcTimestamp authoritativeNow,
            UtcTimestamp replacementExpiresAt) {
        requireActiveOwnership(ownership, expectedVersion, authoritativeNow);
        TaskExecution execution = requireExecution(runningExecution);
        if (phase != ExecutionLeasePhase.PREPARE
                || execution.status() != TaskExecutionStatus.RUNNING) {
            throw new InvalidStateTransitionException(
                    "ExecutionLease", id, phase, ExecutionLeasePhase.RUN);
        }
        requireDuration(
                authoritativeNow,
                replacementExpiresAt,
                ExecutionLeasePhase.RUN,
                "executionLease.expiresAt");
        return copy(
                ExecutionLeasePhase.RUN,
                authoritativeNow,
                replacementExpiresAt,
                Optional.empty(),
                version + 1);
    }

    /** Renews the current phase without changing Worker, Claim Token or Fencing Token. */
    public ExecutionLease heartbeat(
            LeaseOwnership ownership,
            long expectedVersion,
            UtcTimestamp authoritativeNow,
            UtcTimestamp replacementExpiresAt) {
        requireActiveOwnership(ownership, expectedVersion, authoritativeNow);
        requireDuration(
                authoritativeNow,
                replacementExpiresAt,
                phase,
                "executionLease.expiresAt");
        return copy(
                phase,
                authoritativeNow,
                replacementExpiresAt,
                Optional.empty(),
                version + 1);
    }

    /** Releases live ownership explicitly; EXPIRED is reserved for the authoritative sweeper. */
    public ExecutionLease release(
            TaskExecution execution,
            LeaseOwnership ownership,
            ExecutionLeaseReleaseReason reason,
            long expectedVersion,
            UtcTimestamp authoritativeNow) {
        requireActiveOwnership(ownership, expectedVersion, authoritativeNow);
        ExecutionLeaseReleaseReason requiredReason = Objects.requireNonNull(reason, "reason");
        if (requiredReason == ExecutionLeaseReleaseReason.EXPIRED) {
            throw new DomainValidationException(
                    "executionLease.release.reason", "EXPIRED must be committed by expire");
        }
        requireReleaseStatus(requireExecution(execution), requiredReason);
        return copy(
                phase,
                lastHeartbeatAt,
                expiresAt,
                Optional.of(new ExecutionLeaseRelease(requiredReason, authoritativeNow)),
                version + 1);
    }

    /** Commits loss of ownership at or after the exact expiry boundary. */
    public ExecutionLease expire(long expectedVersion, UtcTimestamp authoritativeNow) {
        requireExpectedVersion(expectedVersion);
        requireUnreleased();
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        if (!isExpired(now)) {
            throw new DomainValidationException(
                    "executionLease.expiresAt", "must have elapsed before expiry is committed");
        }
        return copy(
                phase,
                lastHeartbeatAt,
                expiresAt,
                Optional.of(new ExecutionLeaseRelease(
                        ExecutionLeaseReleaseReason.EXPIRED, now)),
                version + 1);
    }

    public ClaimReceipt receipt(ClaimToken claimToken, TaskExecution claimedExecution) {
        ClaimToken requiredToken = Objects.requireNonNull(claimToken, "claimToken");
        if (release.isPresent() || !claimTokenHash.equals(requiredToken.hash())) {
            throw new DomainValidationException(
                    "claimReceipt.claimToken", "must match the active Lease");
        }
        TaskExecution execution = requireExecution(claimedExecution);
        if (execution.status() != TaskExecutionStatus.CLAIMED) {
            throw new DomainValidationException(
                    "claimReceipt.taskExecutionId", "must reference the committed Claim state");
        }
        return new ClaimReceipt(
                id, taskExecutionId, attempt, runtimeId, workerId, requiredToken, fencingToken,
                execution.version(), version, expiresAt);
    }

    public boolean isExpired(UtcTimestamp authoritativeNow) {
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        if (now.compareTo(acquiredAt) < 0) {
            throw new DomainValidationException(
                    "executionLease.authoritativeNow", "must not be before acquisition");
        }
        return now.compareTo(expiresAt) >= 0;
    }

    public boolean isActiveAt(UtcTimestamp authoritativeNow) {
        return release.isEmpty() && !isExpired(authoritativeNow);
    }

    public boolean owns(LeaseOwnership ownership, UtcTimestamp authoritativeNow) {
        return release.isEmpty()
                && !isExpired(authoritativeNow)
                && ownershipMatches(ownership);
    }

    private void requireActiveOwnership(
            LeaseOwnership ownership,
            long expectedVersion,
            UtcTimestamp authoritativeNow) {
        requireExpectedVersion(expectedVersion);
        requireUnreleased();
        if (!owns(ownership, authoritativeNow)) {
            throw new DomainValidationException(
                    "executionLease.ownership", "must match every current active Lease coordinate");
        }
    }

    private boolean ownershipMatches(LeaseOwnership ownership) {
        LeaseOwnership required = Objects.requireNonNull(ownership, "ownership");
        return taskExecutionId.equals(required.taskExecutionId())
                && attempt == required.attempt()
                && runtimeId.equals(required.runtimeId())
                && workerId.equals(required.workerId())
                && claimTokenHash.equals(required.claimTokenHash())
                && fencingToken.equals(required.fencingToken());
    }

    private TaskExecution requireExecution(TaskExecution execution) {
        TaskExecution required = Objects.requireNonNull(execution, "execution");
        if (!taskExecutionId.equals(required.id())
                || attempt != required.attempt()
                || !organizationId.equals(required.scope().organizationId())
                || required.lastFencingToken().filter(fencingToken::equals).isEmpty()) {
            throw new DomainValidationException(
                    "executionLease.taskExecutionId",
                    "must match TaskExecution identity, attempt, Organization and fencing epoch");
        }
        return required;
    }

    private static void requireReleaseStatus(
            TaskExecution execution, ExecutionLeaseReleaseReason reason) {
        TaskExecutionStatus status = execution.status();
        boolean compatible = switch (reason) {
            case COMPLETED -> status == TaskExecutionStatus.COMPLETED;
            case FAILED -> status == TaskExecutionStatus.FAILED;
            case CANCELLED -> status == TaskExecutionStatus.CANCELLED;
            case PAUSED -> status == TaskExecutionStatus.PAUSED;
            case WAITING -> status == TaskExecutionStatus.WAITING;
            case MANUAL_TAKEOVER -> status == TaskExecutionStatus.MANUAL_TAKEOVER;
            case WORKER_SHUTDOWN -> status == TaskExecutionStatus.RECOVERING;
            case EXPIRED -> false;
        };
        if (!compatible) {
            throw new DomainValidationException(
                    "executionLease.release.reason", "must match the TaskExecution state");
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "ExecutionLease", id, expectedVersion, version);
        }
    }

    private void requireUnreleased() {
        if (release.isPresent()) {
            throw new InvalidStateTransitionException(
                    "ExecutionLease", id, phase, phase);
        }
    }

    private void validateTimeline() {
        if (lastHeartbeatAt.compareTo(acquiredAt) < 0
                || expiresAt.compareTo(lastHeartbeatAt) <= 0) {
            throw new DomainValidationException(
                    "executionLease.timeline",
                    "must order acquisition, heartbeat and expiry strictly");
        }
        release.ifPresent(value -> {
            if (value.releasedAt().compareTo(acquiredAt) < 0) {
                throw new DomainValidationException(
                        "executionLease.release.releasedAt", "must not be before acquisition");
            }
            if (value.reason() == ExecutionLeaseReleaseReason.EXPIRED
                    && value.releasedAt().compareTo(expiresAt) < 0) {
                throw new DomainValidationException(
                        "executionLease.release.releasedAt", "must not be before expiry");
            }
        });
    }

    private static void requireDuration(
            UtcTimestamp now,
            UtcTimestamp expiresAt,
            ExecutionLeasePhase phase,
            String field) {
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        UtcTimestamp requiredExpiry = Objects.requireNonNull(expiresAt, "expiresAt");
        Duration duration = Duration.between(requiredNow.value(), requiredExpiry.value());
        Duration maximum = phase == ExecutionLeasePhase.PREPARE
                ? MAX_PREPARE_LEASE_DURATION
                : MAX_RUN_LEASE_DURATION;
        if (duration.compareTo(MIN_LEASE_DURATION) < 0 || duration.compareTo(maximum) > 0) {
            throw new DomainValidationException(
                    field, "must use a bounded duration for the current Lease phase");
        }
    }

    private ExecutionLease copy(
            ExecutionLeasePhase targetPhase,
            UtcTimestamp targetLastHeartbeatAt,
            UtcTimestamp targetExpiresAt,
            Optional<ExecutionLeaseRelease> targetRelease,
            long targetVersion) {
        return new ExecutionLease(
                id, organizationId, environment, taskExecutionId, attempt, runtimeId, workerId,
                claimTokenHash, fencingToken, targetPhase, acquiredAt, targetLastHeartbeatAt,
                targetExpiresAt, targetRelease, targetVersion);
    }

    public ExecutionLeaseId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public RuntimeEnvironment environment() { return environment; }
    public TaskExecutionId taskExecutionId() { return taskExecutionId; }
    public int attempt() { return attempt; }
    public ExecutionRuntimeId runtimeId() { return runtimeId; }
    public RuntimeWorkerId workerId() { return workerId; }
    public ClaimTokenHash claimTokenHash() { return claimTokenHash; }
    public FencingToken fencingToken() { return fencingToken; }
    public ExecutionLeasePhase phase() { return phase; }
    public UtcTimestamp acquiredAt() { return acquiredAt; }
    public UtcTimestamp lastHeartbeatAt() { return lastHeartbeatAt; }
    public UtcTimestamp expiresAt() { return expiresAt; }
    public Optional<ExecutionLeaseRelease> release() { return release; }
    public long version() { return version; }
}
