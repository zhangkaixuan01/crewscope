package io.crewscope.application.action;

import io.crewscope.application.github.GitHubRepositoryPolicy;
import io.crewscope.domain.action.ActionAuthorityFacts;
import io.crewscope.domain.action.PlannedAction;

/** Resolves the current Organization repository allowlist used by GitHub Preflight. */
public interface GitHubRepositoryPolicyResolver {

    GitHubRepositoryPolicy resolve(ActionAuthorityFacts authority, PlannedAction action);
}
