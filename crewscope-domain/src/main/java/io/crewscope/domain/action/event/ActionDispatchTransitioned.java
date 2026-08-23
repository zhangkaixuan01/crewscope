package io.crewscope.domain.action.event;

import io.crewscope.domain.action.ActionDispatch;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Low-cardinality scheduler state fact without lease secret or Provider parameters. */
public record ActionDispatchTransitioned(
        UUID actionDispatchId,
        UUID actionBundleId,
        UUID plannedActionId,
        String actionDigest,
        String status,
        Optional<Long> fencingToken,
        int claimAttempts,
        int reconciliationAttempts,
        long dispatchVersion) implements DomainEvent {

    public ActionDispatchTransitioned {
        actionDispatchId = Objects.requireNonNull(actionDispatchId, "actionDispatchId");
        actionBundleId = Objects.requireNonNull(actionBundleId, "actionBundleId");
        plannedActionId = Objects.requireNonNull(plannedActionId, "plannedActionId");
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        status = Objects.requireNonNull(status, "status");
        fencingToken = Objects.requireNonNull(fencingToken, "fencingToken");
        if (claimAttempts < 0 || reconciliationAttempts < 0 || dispatchVersion < 0) {
            throw new IllegalArgumentException("Action Dispatch event counters must not be negative");
        }
    }

    public static ActionDispatchTransitioned from(ActionDispatch dispatch) {
        ActionDispatch value = Objects.requireNonNull(dispatch, "dispatch");
        return new ActionDispatchTransitioned(
                value.id().value(),
                value.bundleId().value(),
                value.actionId().value(),
                value.actionDigest().toString(),
                value.status().name(),
                value.claim().map(claim -> claim.fencingToken().value()),
                value.claimAttempts(),
                value.reconciliationAttempts(),
                value.version());
    }
}
