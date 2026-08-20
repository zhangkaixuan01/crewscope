package io.crewscope.application.coding;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import java.util.Objects;

/** Public-safe Preflight result; resolved host filesystem facts remain in infrastructure. */
public record RepositoryBindingPreflightResult(
        RepositoryKey repositoryKey,
        RepositoryBranchName baselineRef,
        RepositoryCommitId baselineCommit) {

    public RepositoryBindingPreflightResult {
        repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        baselineRef = Objects.requireNonNull(baselineRef, "baselineRef");
        baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
    }
}
