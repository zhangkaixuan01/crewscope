package io.crewscope.application.github;

/** Stable safe rejection classes for inbound GitHub Webhooks. */
public enum GitHubWebhookErrorCode {
    SIGNATURE_INVALID,
    EVENT_UNSUPPORTED,
    IDENTITY_MISMATCH,
    DELIVERY_CONFLICT,
    PAYLOAD_INVALID,
    SECRET_UNAVAILABLE
}
