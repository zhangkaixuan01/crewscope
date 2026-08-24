package io.crewscope.application.github;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Current authorization, catalog, rate-limit and inbound Webhook readiness summary. */
public record GitHubAuthorizationHealthView(
        String authorizationStatus,
        boolean connectionUsable,
        boolean grantUsable,
        boolean credentialUsable,
        boolean profileCurrent,
        int deliverableRepositoryCount,
        String webhookStatus,
        Optional<GitHubRateLimitHealth> rateLimit) {

    public GitHubAuthorizationHealthView {
        authorizationStatus = requireText(authorizationStatus, "authorizationStatus");
        if (deliverableRepositoryCount < 0) {
            throw new IllegalArgumentException("deliverableRepositoryCount must not be negative");
        }
        webhookStatus = requireText(webhookStatus, "webhookStatus");
        rateLimit = Objects.requireNonNull(rateLimit, "rateLimit");
    }

    public record GitHubRateLimitHealth(
            String resource, long limit, long remaining, UtcTimestamp resetsAt, UtcTimestamp observedAt) {

        public GitHubRateLimitHealth {
            resource = requireText(resource, "resource");
            if (limit < 0 || remaining < 0 || remaining > limit) {
                throw new IllegalArgumentException("rateLimit is invalid");
            }
            Objects.requireNonNull(resetsAt, "resetsAt");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
