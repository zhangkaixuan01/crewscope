package io.crewscope.application.collaboration;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Safe live-health evidence; external identity, endpoint, token and response body are absent. */
public record LarkProviderHealth(
        LarkProviderHealthStatus status,
        boolean retryable,
        Optional<Duration> retryAfter,
        String evidenceCode,
        UtcTimestamp checkedAt) {

    public LarkProviderHealth {
        status = Objects.requireNonNull(status, "status");
        retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        if (evidenceCode == null || !evidenceCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("Lark health evidence code is invalid");
        }
        retryAfter.ifPresent(value -> {
            if (value.isZero() || value.isNegative()
                    || value.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException(
                        "Lark health Retry-After must be within (0, 5m]");
            }
        });
        if (status == LarkProviderHealthStatus.HEALTHY && (retryable || retryAfter.isPresent())) {
            throw new IllegalArgumentException("Healthy Lark Provider cannot request retry");
        }
    }

    public boolean healthy() {
        return status == LarkProviderHealthStatus.HEALTHY;
    }

    public static LarkProviderHealth healthy(UtcTimestamp checkedAt) {
        return new LarkProviderHealth(
                LarkProviderHealthStatus.HEALTHY,
                false,
                Optional.empty(),
                "LARK_PROVIDER_HEALTHY",
                checkedAt);
    }

    public static LarkProviderHealth authorizationUnavailable(UtcTimestamp checkedAt) {
        return new LarkProviderHealth(
                LarkProviderHealthStatus.AUTHORIZATION_UNAVAILABLE,
                false,
                Optional.empty(),
                "LARK_AUTHORIZATION_UNAVAILABLE",
                checkedAt);
    }

    @Override
    public String toString() {
        return "LarkProviderHealth[status=" + status + ", retryable=" + retryable
                + ", evidenceCode=" + evidenceCode + ", identity=REDACTED]";
    }
}
