package io.crewscope.domain.runtime;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Durable identity, health and routable capacity facts of one runtime Worker. */
public final class RuntimeWorker {

    public static final Duration MIN_HEARTBEAT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration MAX_HEARTBEAT_TIMEOUT = Duration.ofMinutes(10);

    private static final Map<RuntimeWorkerStatus, Set<RuntimeWorkerStatus>> TRANSITIONS = Map.of(
            RuntimeWorkerStatus.REGISTERED, EnumSet.of(
                    RuntimeWorkerStatus.ACTIVE, RuntimeWorkerStatus.DISABLED),
            RuntimeWorkerStatus.ACTIVE, EnumSet.of(
                    RuntimeWorkerStatus.DRAINING, RuntimeWorkerStatus.DISABLED),
            RuntimeWorkerStatus.DRAINING, EnumSet.of(
                    RuntimeWorkerStatus.ACTIVE, RuntimeWorkerStatus.DISABLED),
            RuntimeWorkerStatus.DISABLED, EnumSet.of(RuntimeWorkerStatus.ACTIVE));

    private final RuntimeWorkerId id;
    private final OrganizationId organizationId;
    private final RuntimeEnvironment environment;
    private final ExecutionRuntimeId runtimeId;
    private final String stableKey;
    private final RuntimeProfile profile;
    private final RuntimeCapabilities capabilities;
    private final RuntimeWorkerCapacity capacity;
    private final RuntimeWorkerStatus status;
    private final UtcTimestamp lastHeartbeatAt;
    private final long heartbeatSequence;
    private final long version;
    private final AuditMetadata audit;

    private RuntimeWorker(
            RuntimeWorkerId id,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId,
            String stableKey,
            RuntimeProfile profile,
            RuntimeCapabilities capabilities,
            RuntimeWorkerCapacity capacity,
            RuntimeWorkerStatus status,
            UtcTimestamp lastHeartbeatAt,
            long heartbeatSequence,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        this.stableKey = requireStableKey(stableKey);
        this.profile = Objects.requireNonNull(profile, "profile");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.status = Objects.requireNonNull(status, "status");
        this.lastHeartbeatAt = Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
        if (heartbeatSequence < 0) {
            throw new DomainValidationException(
                    "runtimeWorker.heartbeatSequence", "must not be negative");
        }
        this.heartbeatSequence = heartbeatSequence;
        if (version < 0) {
            throw new DomainValidationException("runtimeWorker.version", "must not be negative");
        }
        this.version = version;
        this.audit = Objects.requireNonNull(audit, "audit");
        if (lastHeartbeatAt.compareTo(audit.createdAt()) < 0
                || lastHeartbeatAt.compareTo(audit.updatedAt()) > 0) {
            throw new DomainValidationException(
                    "runtimeWorker.lastHeartbeatAt", "must be within the Worker audit lifetime");
        }
    }

    /** Registers one stable Worker under an active Runtime without making it claimable yet. */
    public static RuntimeWorker register(
            RuntimeWorkerId id,
            ExecutionRuntime runtime,
            String stableKey,
            RuntimeProfile profile,
            RuntimeCapabilities capabilities,
            RuntimeWorkerCapacity capacity,
            Principal actor,
            UtcTimestamp occurredAt) {
        ExecutionRuntime requiredRuntime = Objects.requireNonNull(runtime, "runtime");
        if (requiredRuntime.status() != ExecutionRuntimeStatus.ACTIVE) {
            throw new DomainValidationException(
                    "runtimeWorker.runtimeId", "must reference an active Runtime");
        }
        RuntimeCapabilities workerCapabilities = Objects.requireNonNull(
                capabilities, "capabilities");
        if (!workerCapabilities.isSubsetOf(requiredRuntime.capabilities())) {
            throw new DomainValidationException(
                    "runtimeWorker.capabilities", "must be a subset of Runtime capabilities");
        }
        PrincipalId actorId = RuntimeActorPolicy.requireActiveInOrganization(
                actor, requiredRuntime.organizationId(), "runtimeWorker.createdByPrincipalId");
        return new RuntimeWorker(
                id, requiredRuntime.organizationId(), requiredRuntime.environment(),
                requiredRuntime.id(), stableKey, profile, workerCapabilities, capacity,
                RuntimeWorkerStatus.REGISTERED, occurredAt, 0, 0,
                AuditMetadata.createdBy(actorId, occurredAt));
    }

    public static RuntimeWorker reconstitute(
            RuntimeWorkerId id,
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionRuntimeId runtimeId,
            String stableKey,
            RuntimeProfile profile,
            RuntimeCapabilities capabilities,
            RuntimeWorkerCapacity capacity,
            RuntimeWorkerStatus status,
            UtcTimestamp lastHeartbeatAt,
            long heartbeatSequence,
            long version,
            AuditMetadata audit) {
        return new RuntimeWorker(
                id, organizationId, environment, runtimeId, stableKey, profile, capabilities,
                capacity, status, lastHeartbeatAt, heartbeatSequence, version, audit);
    }

    /** Activates a registered, disabled or drained Worker after an explicit successful heartbeat. */
    public RuntimeWorker activate(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                RuntimeWorkerStatus.ACTIVE, expectedVersion, actor, occurredAt, true);
    }

    /** Stops new claims while allowing the reported active load to finish. */
    public RuntimeWorker beginDrain(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                RuntimeWorkerStatus.DRAINING, expectedVersion, actor, occurredAt, false);
    }

    public RuntimeWorker disable(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                RuntimeWorkerStatus.DISABLED, expectedVersion, actor, occurredAt, false);
    }

    /** Records an authoritative Worker heartbeat and its current capability/capacity snapshot. */
    public RuntimeWorker heartbeat(
            ExecutionRuntime runtime,
            long expectedVersion,
            RuntimeCapabilities reportedCapabilities,
            RuntimeWorkerCapacity reportedCapacity,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status == RuntimeWorkerStatus.DISABLED) {
            throw new InvalidStateTransitionException(
                    "RuntimeWorker", id, status, status);
        }
        ExecutionRuntime currentRuntime = requireRuntimeLineage(runtime);
        RuntimeCapabilities nextCapabilities = Objects.requireNonNull(
                reportedCapabilities, "reportedCapabilities");
        if (!nextCapabilities.isSubsetOf(currentRuntime.capabilities())) {
            throw new DomainValidationException(
                    "runtimeWorker.capabilities", "must be a subset of Runtime capabilities");
        }
        RuntimeWorkerCapacity nextCapacity = Objects.requireNonNull(
                reportedCapacity, "reportedCapacity");
        PrincipalId actorId = requireActor(actor);
        return copy(nextCapabilities, nextCapacity, status, occurredAt,
                heartbeatSequence + 1, version + 1, audit.modifiedBy(actorId, occurredAt));
    }

    /** A Worker is claimable only while active, fresh, capable and below its capacity ceiling. */
    public boolean canClaim(
            ExecutionRuntime runtime,
            RuntimeCapabilities required,
            UtcTimestamp authoritativeNow,
            Duration heartbeatTimeout) {
        ExecutionRuntime currentRuntime = requireRuntime(runtime);
        return currentRuntime.status() == ExecutionRuntimeStatus.ACTIVE
                && status == RuntimeWorkerStatus.ACTIVE
                && isHeartbeatFresh(authoritativeNow, heartbeatTimeout)
                && capacity.hasAvailability()
                && capabilities.supports(required)
                && currentRuntime.capabilities().supports(required);
    }

    public boolean isHeartbeatFresh(
            UtcTimestamp authoritativeNow, Duration heartbeatTimeout) {
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        Duration timeout = requireHeartbeatTimeout(heartbeatTimeout);
        if (now.compareTo(lastHeartbeatAt) < 0) {
            throw new DomainValidationException(
                    "runtimeWorker.authoritativeNow", "must not be before the last heartbeat");
        }
        return Duration.between(lastHeartbeatAt.value(), now.value()).compareTo(timeout) <= 0;
    }

    private RuntimeWorker transition(
            RuntimeWorkerStatus target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt,
            boolean recordsHeartbeat) {
        requireExpectedVersion(expectedVersion);
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new InvalidStateTransitionException("RuntimeWorker", id, status, target);
        }
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp targetHeartbeatAt = recordsHeartbeat ? occurredAt : lastHeartbeatAt;
        long targetHeartbeatSequence = recordsHeartbeat
                ? heartbeatSequence + 1
                : heartbeatSequence;
        return copy(capabilities, capacity, target, targetHeartbeatAt,
                targetHeartbeatSequence, version + 1, audit.modifiedBy(actorId, occurredAt));
    }

    private ExecutionRuntime requireRuntime(ExecutionRuntime runtime) {
        ExecutionRuntime required = requireRuntimeLineage(runtime);
        if (!capabilities.isSubsetOf(required.capabilities())) {
            throw new DomainValidationException(
                    "runtimeWorker.capabilities", "must be a subset of Runtime capabilities");
        }
        return required;
    }

    private ExecutionRuntime requireRuntimeLineage(ExecutionRuntime runtime) {
        ExecutionRuntime required = Objects.requireNonNull(runtime, "runtime");
        if (!runtimeId.equals(required.id())
                || !organizationId.equals(required.organizationId())
                || !environment.equals(required.environment())) {
            throw new DomainValidationException(
                    "runtimeWorker.runtimeId",
                    "must share Runtime identity, Organization and environment");
        }
        return required;
    }

    private PrincipalId requireActor(Principal actor) {
        return RuntimeActorPolicy.requireActiveInOrganization(
                actor, organizationId, "runtimeWorker.updatedByPrincipalId");
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "RuntimeWorker", id, expectedVersion, version);
        }
    }

    private RuntimeWorker copy(
            RuntimeCapabilities targetCapabilities,
            RuntimeWorkerCapacity targetCapacity,
            RuntimeWorkerStatus targetStatus,
            UtcTimestamp targetHeartbeatAt,
            long targetHeartbeatSequence,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new RuntimeWorker(
                id, organizationId, environment, runtimeId, stableKey, profile,
                targetCapabilities, targetCapacity, targetStatus, targetHeartbeatAt,
                targetHeartbeatSequence, targetVersion, targetAudit);
    }

    private static Duration requireHeartbeatTimeout(Duration value) {
        Duration required = Objects.requireNonNull(value, "heartbeatTimeout");
        if (required.compareTo(MIN_HEARTBEAT_TIMEOUT) < 0
                || required.compareTo(MAX_HEARTBEAT_TIMEOUT) > 0) {
            throw new DomainValidationException(
                    "runtimeWorker.heartbeatTimeout", "must be between 5 seconds and 10 minutes");
        }
        return required;
    }

    static String requireStableKey(String value) {
        String required = ExecutionRuntime.requireText(
                value, "runtimeWorker.stableKey", 128);
        if (!required.matches("[a-z0-9][a-z0-9._:-]{0,127}")) {
            throw new DomainValidationException(
                    "runtimeWorker.stableKey", "must use a safe stable Worker key");
        }
        return required;
    }

    public RuntimeWorkerId id() { return id; }
    public OrganizationId organizationId() { return organizationId; }
    public RuntimeEnvironment environment() { return environment; }
    public ExecutionRuntimeId runtimeId() { return runtimeId; }
    public String stableKey() { return stableKey; }
    public RuntimeProfile profile() { return profile; }
    public RuntimeCapabilities capabilities() { return capabilities; }
    public RuntimeWorkerCapacity capacity() { return capacity; }
    public RuntimeWorkerStatus status() { return status; }
    public UtcTimestamp lastHeartbeatAt() { return lastHeartbeatAt; }
    public long heartbeatSequence() { return heartbeatSequence; }
    public long version() { return version; }
    public AuditMetadata audit() { return audit; }
}
