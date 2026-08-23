package io.crewscope.application.github;

/** Connector Worker boundary for publishing one confirmed GitHub branch action. */
public interface GitHubPushPort {

    GitHubPushResult pushBranch(PushGitHubBranchRequest request);

    /** Queries the remote branch without issuing Push or mutating Provider state. */
    GitHubBranchQueryResult queryBranch(PushGitHubBranchRequest request);
}
