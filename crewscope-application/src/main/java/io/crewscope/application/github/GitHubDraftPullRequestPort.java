package io.crewscope.application.github;

/** Action Worker boundary for an exact idempotent GitHub Draft PR operation. */
public interface GitHubDraftPullRequestPort {

    GitHubDraftPullRequestResult ensureDraft(
            CreateGitHubDraftPullRequestRequest request);

    /** Queries the exact PR coordinates without creating or reopening a Pull Request. */
    GitHubDraftPullRequestQueryResult queryDraft(
            CreateGitHubDraftPullRequestRequest request);
}
