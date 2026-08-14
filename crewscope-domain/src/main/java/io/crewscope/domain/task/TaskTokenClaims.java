package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Objects;

/** Closed short-lived claims passed only to the trusted Task Token signing boundary. */
public record TaskTokenClaims(
        String audience,
        TaskCredentialGrantId grantId,
        TaskTokenJti jti,
        TaskTokenGrantScope scope,
        UtcTimestamp issuedAt,
        UtcTimestamp expiresAt) {

    public static final String AUDIENCE = "crewscope-task-runtime";
    public static final Duration MIN_LIFETIME = Duration.ofSeconds(5);
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(15);

    public TaskTokenClaims {
        if (!AUDIENCE.equals(audience)) {
            throw new DomainValidationException(
                    "taskToken.audience", "must identify the CrewScope Task Runtime");
        }
        grantId = Objects.requireNonNull(grantId, "grantId");
        jti = Objects.requireNonNull(jti, "jti");
        scope = Objects.requireNonNull(scope, "scope");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        Duration lifetime = Duration.between(issuedAt.value(), expiresAt.value());
        if (lifetime.compareTo(MIN_LIFETIME) < 0
                || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new DomainValidationException(
                    "taskToken.expiresAt", "must use a lifetime from 5 seconds to 15 minutes");
        }
    }

    /** Rejects use before issuance and at or after the exact expiry boundary. */
    public void requireValidAt(UtcTimestamp authoritativeNow) {
        UtcTimestamp now = Objects.requireNonNull(authoritativeNow, "authoritativeNow");
        if (now.compareTo(issuedAt) < 0 || now.compareTo(expiresAt) >= 0) {
            throw new DomainValidationException(
                    "taskToken.expiresAt", "must be valid at the authoritative time");
        }
    }

    @Override
    public String toString() {
        return "TaskTokenClaims[audience=" + audience
                + ", grantId=" + grantId
                + ", scope=" + scope
                + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt
                + ", jti=[REDACTED]]";
    }
}
