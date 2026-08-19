package io.crewscope.infrastructure.workspace.repository;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import java.util.Objects;

/** Validated repository and immutable baseline selected for subsequent infrastructure work. */
public record BaselinePreflightResult(
        ManagedRepository repository,
        RepositoryBranchName baselineRef,
        RepositoryCommitId baselineCommit) {

    public BaselinePreflightResult {
        repository = Objects.requireNonNull(repository, "repository");
        baselineRef = Objects.requireNonNull(baselineRef, "baselineRef");
        baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
    }
}
