package io.crewscope.domain.action.event;

import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.shared.DomainEvent;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Safe event for exact human authorization without action parameters or credentials. */
public record ActionBundleConfirmed(
        UUID confirmationId,
        UUID actionBundleId,
        String bundleDigest,
        List<String> actionDigests,
        UUID confirmedByPrincipalId,
        String validUntil) implements DomainEvent {

    public ActionBundleConfirmed {
        confirmationId = Objects.requireNonNull(confirmationId, "confirmationId");
        actionBundleId = Objects.requireNonNull(actionBundleId, "actionBundleId");
        bundleDigest = Objects.requireNonNull(bundleDigest, "bundleDigest");
        actionDigests = List.copyOf(Objects.requireNonNull(actionDigests, "actionDigests"));
        confirmedByPrincipalId = Objects.requireNonNull(
                confirmedByPrincipalId, "confirmedByPrincipalId");
        validUntil = Objects.requireNonNull(validUntil, "validUntil");
    }

    public static ActionBundleConfirmed from(Confirmation confirmation) {
        Confirmation value = Objects.requireNonNull(confirmation, "confirmation");
        return new ActionBundleConfirmed(
                value.id().value(),
                value.bundleId().value(),
                value.bundleDigest().toString(),
                value.actions().stream().map(action -> action.actionDigest().toString()).toList(),
                value.confirmedByPrincipalId().value(),
                value.validUntil().toString());
    }
}
