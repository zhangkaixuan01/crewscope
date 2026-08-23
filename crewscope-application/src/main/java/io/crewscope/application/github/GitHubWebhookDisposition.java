package io.crewscope.application.github;

/** Durable append outcome for one Connection-scoped Webhook delivery. */
public enum GitHubWebhookDisposition {
    ACCEPTED,
    DUPLICATE
}
