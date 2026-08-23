package io.crewscope.domain.action;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Normalized, deduplicated Provider fact accepted from one trusted source. */
public record ExternalObservation(
        ExternalObservationKey observationKey,
        PlannedActionId actionId,
        ActionDigest actionDigest,
        ExternalResultIdentity identity,
        ExternalObjectStatus status,
        Optional<Long> providerVersion,
        Optional<UtcTimestamp> providerUpdatedAt,
        ExternalResultSource source,
        ActionEvidenceReference evidence,
        UtcTimestamp observedAt) {

    public ExternalObservation {
        observationKey = Objects.requireNonNull(observationKey, "observationKey");
        actionId = Objects.requireNonNull(actionId, "actionId");
        actionDigest = Objects.requireNonNull(actionDigest, "actionDigest");
        identity = Objects.requireNonNull(identity, "identity");
        status = Objects.requireNonNull(status, "status");
        if (!status.supports(identity.objectType())) {
            throw new DomainValidationException(
                    "externalObservation.status", "is incompatible with the external object type");
        }
        providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        providerVersion.ifPresent(value -> {
            if (value < 1) {
                throw new DomainValidationException(
                        "externalObservation.providerVersion", "must be positive");
            }
        });
        providerUpdatedAt = Objects.requireNonNull(providerUpdatedAt, "providerUpdatedAt");
        if (providerVersion.isEmpty() && providerUpdatedAt.isEmpty()) {
            throw new DomainValidationException(
                    "externalObservation.providerUpdatedAt",
                    "is required when the Provider has no monotonic version");
        }
        source = Objects.requireNonNull(source, "source");
        evidence = Objects.requireNonNull(evidence, "evidence");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (providerUpdatedAt.isPresent()
                && providerUpdatedAt.orElseThrow().compareTo(observedAt) > 0) {
            throw new DomainValidationException(
                    "externalObservation.providerUpdatedAt", "must not be after observation time");
        }
    }
}
