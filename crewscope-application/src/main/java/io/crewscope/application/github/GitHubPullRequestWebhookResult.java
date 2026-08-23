package io.crewscope.application.github;

import io.crewscope.domain.action.ExternalObservation;
import java.util.Objects;

/** Normalized secret-free Webhook fact and its durable deduplication outcome. */
public record GitHubPullRequestWebhookResult(
        GitHubWebhookDisposition disposition,
        ExternalObservation observation) {

    public GitHubPullRequestWebhookResult {
        disposition = Objects.requireNonNull(disposition, "disposition");
        observation = Objects.requireNonNull(observation, "observation");
    }
}
