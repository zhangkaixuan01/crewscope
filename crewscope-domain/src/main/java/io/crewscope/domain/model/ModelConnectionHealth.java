package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Sanitized verification snapshot tied to one exact credential version. */
public record ModelConnectionHealth(
        ModelConnectionHealthStatus status,
        ModelCredentialVersion credentialVersion,
        Optional<UtcTimestamp> checkedAt,
        Optional<UtcTimestamp> lastHealthyAt,
        int consecutiveFailures,
        Optional<ModelConnectionHealthFailureCode> failureCode) {

    public ModelConnectionHealth {
        status = Objects.requireNonNull(status, "status");
        credentialVersion = Objects.requireNonNull(credentialVersion, "credentialVersion");
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        lastHealthyAt = Objects.requireNonNull(lastHealthyAt, "lastHealthyAt");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        requireValidShape(status, checkedAt, lastHealthyAt, consecutiveFailures, failureCode);
    }

    public static ModelConnectionHealth unknown(ModelCredentialVersion credentialVersion) {
        return new ModelConnectionHealth(
                ModelConnectionHealthStatus.UNKNOWN,
                credentialVersion,
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty());
    }

    public ModelConnectionHealth recordSuccess(
            ModelCredentialVersion expectedCredentialVersion, UtcTimestamp occurredAt) {
        requireCredentialVersion(expectedCredentialVersion);
        UtcTimestamp checked = requireLaterCheck(occurredAt);
        return new ModelConnectionHealth(
                ModelConnectionHealthStatus.HEALTHY,
                credentialVersion,
                Optional.of(checked),
                Optional.of(checked),
                0,
                Optional.empty());
    }

    public ModelConnectionHealth recordFailure(
            ModelCredentialVersion expectedCredentialVersion,
            ModelConnectionHealthFailureCode failureCode,
            UtcTimestamp occurredAt) {
        requireCredentialVersion(expectedCredentialVersion);
        UtcTimestamp checked = requireLaterCheck(occurredAt);
        if (consecutiveFailures == Integer.MAX_VALUE) {
            throw new DomainValidationException(
                    "modelConnection.health.consecutiveFailures", "must not overflow");
        }
        return new ModelConnectionHealth(
                ModelConnectionHealthStatus.UNHEALTHY,
                credentialVersion,
                Optional.of(checked),
                lastHealthyAt,
                consecutiveFailures + 1,
                Optional.of(Objects.requireNonNull(failureCode, "failureCode")));
    }

    public boolean isHealthyFor(ModelCredentialVersion currentCredentialVersion) {
        return status == ModelConnectionHealthStatus.HEALTHY
                && credentialVersion.equals(currentCredentialVersion);
    }

    private void requireCredentialVersion(ModelCredentialVersion expected) {
        if (!credentialVersion.equals(Objects.requireNonNull(expected, "expectedCredentialVersion"))) {
            throw new DomainValidationException(
                    "modelConnection.health.credentialVersion",
                    "must match the currently bound credential version");
        }
    }

    private UtcTimestamp requireLaterCheck(UtcTimestamp occurredAt) {
        UtcTimestamp required = Objects.requireNonNull(occurredAt, "occurredAt");
        if (checkedAt.filter(value -> required.compareTo(value) <= 0).isPresent()) {
            throw new DomainValidationException(
                    "modelConnection.health.checkedAt",
                    "must be later than the current health check");
        }
        return required;
    }

    private static void requireValidShape(
            ModelConnectionHealthStatus status,
            Optional<UtcTimestamp> checkedAt,
            Optional<UtcTimestamp> lastHealthyAt,
            int consecutiveFailures,
            Optional<ModelConnectionHealthFailureCode> failureCode) {
        if (lastHealthyAt.isPresent()
                && (checkedAt.isEmpty()
                        || lastHealthyAt.orElseThrow().compareTo(checkedAt.orElseThrow()) > 0)) {
            throw new DomainValidationException(
                    "modelConnection.health.lastHealthyAt",
                    "must not be after the latest health check");
        }
        boolean valid = switch (status) {
            case UNKNOWN -> checkedAt.isEmpty()
                    && lastHealthyAt.isEmpty()
                    && consecutiveFailures == 0
                    && failureCode.isEmpty();
            case HEALTHY -> checkedAt.isPresent()
                    && lastHealthyAt.equals(checkedAt)
                    && consecutiveFailures == 0
                    && failureCode.isEmpty();
            case UNHEALTHY -> checkedAt.isPresent()
                    && consecutiveFailures > 0
                    && failureCode.isPresent();
        };
        if (!valid) {
            throw new DomainValidationException(
                    "modelConnection.health", "has an invalid status shape");
        }
    }
}
