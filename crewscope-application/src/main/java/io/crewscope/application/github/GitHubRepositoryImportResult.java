package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import java.util.Objects;

/** Safe result of a Worker import; no filesystem path or Git output is retained. */
public record GitHubRepositoryImportResult(RepositoryKey repositoryKey, RepositoryCommitId baselineCommit) {
    public GitHubRepositoryImportResult {
        Objects.requireNonNull(repositoryKey, "repositoryKey");
        Objects.requireNonNull(baselineCommit, "baselineCommit");
    }
}
