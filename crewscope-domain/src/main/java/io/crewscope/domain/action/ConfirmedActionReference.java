package io.crewscope.domain.action;

import java.util.Objects;

/** Exact ordered child digest covered by a Confirmation. */
public record ConfirmedActionReference(
        PlannedActionId actionId, int sequence, ActionDigest actionDigest) {

    public ConfirmedActionReference {
        actionId = Objects.requireNonNull(actionId, "actionId");
        if (sequence < 1) {
            throw new IllegalArgumentException("Confirmed action sequence must be positive");
        }
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
    }

    public static ConfirmedActionReference from(PlannedAction action) {
        PlannedAction required = Objects.requireNonNull(action, "action");
        return new ConfirmedActionReference(
                required.id(), required.sequence(), required.digest());
    }
}
