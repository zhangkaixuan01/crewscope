package io.crewscope.domain.action.event;

import io.crewscope.domain.action.ExternalMergeOutcome;
import io.crewscope.domain.action.ExternalResult;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Safe merged external status fact without raw Webhook payload or external object identifier. */
public record ExternalResultMerged(
        UUID externalResultId,
        UUID plannedActionId,
        String externalIdentityHash,
        String externalObjectType,
        String providerStatus,
        Optional<Long> providerVersion,
        String source,
        String mergeOutcome,
        long resultVersion) implements DomainEvent {

    public ExternalResultMerged {
        externalResultId = Objects.requireNonNull(externalResultId, "externalResultId");
        plannedActionId = Objects.requireNonNull(plannedActionId, "plannedActionId");
        externalIdentityHash = Objects.requireNonNull(
                externalIdentityHash, "externalIdentityHash");
        externalObjectType = Objects.requireNonNull(externalObjectType, "externalObjectType");
        providerStatus = Objects.requireNonNull(providerStatus, "providerStatus");
        providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        source = Objects.requireNonNull(source, "source");
        mergeOutcome = Objects.requireNonNull(mergeOutcome, "mergeOutcome");
        if (resultVersion < 0) {
            throw new IllegalArgumentException("ExternalResult event version must not be negative");
        }
    }

    public static ExternalResultMerged from(
            ExternalResult result, ExternalMergeOutcome outcome) {
        ExternalResult value = Objects.requireNonNull(result, "result");
        return new ExternalResultMerged(
                value.id().value(),
                value.actionId().value(),
                value.identity().safeHash(),
                value.identity().objectType().name(),
                value.status().name(),
                value.providerVersion(),
                value.lastSource().name(),
                Objects.requireNonNull(outcome, "outcome").name(),
                value.version());
    }
}
