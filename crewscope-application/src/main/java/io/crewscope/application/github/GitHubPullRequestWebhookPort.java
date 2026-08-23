package io.crewscope.application.github;

/** Inbound verified and durably deduplicated GitHub Pull Request Webhook boundary. */
public interface GitHubPullRequestWebhookPort {

    GitHubPullRequestWebhookResult accept(
            AcceptGitHubPullRequestWebhookRequest request);
}
