package io.crewscope.domain.action.event;

import io.crewscope.domain.action.Confirmation;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Safe event recording withdrawal of unused human Action authorization. */
public record ActionConfirmationCancelled(
        UUID confirmationId,
        UUID actionBundleId,
        String bundleDigest,
        String cancellationReason,
        long confirmationVersion) implements DomainEvent {

    public ActionConfirmationCancelled {
        confirmationId = Objects.requireNonNull(confirmationId, "confirmationId");
        actionBundleId = Objects.requireNonNull(actionBundleId, "actionBundleId");
        bundleDigest = Objects.requireNonNull(bundleDigest, "bundleDigest");
        cancellationReason = Objects.requireNonNull(cancellationReason, "cancellationReason");
        if (confirmationVersion < 1) {
            throw new IllegalArgumentException("Cancelled Confirmation version must be positive");
        }
    }

    public static ActionConfirmationCancelled from(Confirmation confirmation) {
        Confirmation value = Objects.requireNonNull(confirmation, "confirmation");
        return new ActionConfirmationCancelled(
                value.id().value(),
                value.bundleId().value(),
                value.bundleDigest().toString(),
                value.cancellationReason().orElseThrow().name(),
                value.version());
    }
}
