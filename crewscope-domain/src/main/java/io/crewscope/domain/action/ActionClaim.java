package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;

/** Exact leased Action Worker ownership returned after a committed claim. */
public record ActionClaim(
        ActionDispatchId dispatchId,
        PlannedActionId actionId,
        ActionWorkerId workerId,
        ActionFencingToken fencingToken,
        ActionClaimMode mode,
        UtcTimestamp acquiredAt,
        UtcTimestamp lastHeartbeatAt,
        UtcTimestamp leaseUntil) {

    public static final Duration MIN_LEASE = Duration.ofSeconds(5);
    public static final Duration MAX_LEASE = Duration.ofMinutes(5);

    public ActionClaim {
        dispatchId = Objects.requireNonNull(dispatchId, "dispatchId");
        actionId = Objects.requireNonNull(actionId, "actionId");
        workerId = Objects.requireNonNull(workerId, "workerId");
        fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
        mode = Objects.requireNonNull(mode, "mode");
        acquiredAt = Objects.requireNonNull(acquiredAt, "acquiredAt");
        lastHeartbeatAt = Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt");
        leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (lastHeartbeatAt.compareTo(acquiredAt) < 0) {
            throw new DomainValidationException(
                    "actionClaim.lastHeartbeatAt", "must not precede acquisition");
        }
        requireLease(lastHeartbeatAt, leaseUntil);
    }

    public ActionClaim heartbeat(UtcTimestamp now, UtcTimestamp replacementLeaseUntil) {
        UtcTimestamp requiredNow = Objects.requireNonNull(now, "now");
        if (!isActiveAt(requiredNow)) {
            throw new DomainValidationException("actionClaim.leaseUntil", "must still be active");
        }
        requireLease(requiredNow, replacementLeaseUntil);
        return new ActionClaim(
                dispatchId, actionId, workerId, fencingToken, mode,
                acquiredAt, requiredNow, replacementLeaseUntil);
    }

    public boolean isActiveAt(UtcTimestamp now) {
        return Objects.requireNonNull(now, "now").compareTo(leaseUntil) < 0;
    }

    private static void requireLease(UtcTimestamp start, UtcTimestamp end) {
        UtcTimestamp requiredEnd = Objects.requireNonNull(end, "leaseUntil");
        Duration duration = Duration.between(start.value(), requiredEnd.value());
        if (duration.compareTo(MIN_LEASE) < 0 || duration.compareTo(MAX_LEASE) > 0) {
            throw new DomainValidationException(
                    "actionClaim.leaseUntil", "must be between 5 seconds and 5 minutes");
        }
    }
}
