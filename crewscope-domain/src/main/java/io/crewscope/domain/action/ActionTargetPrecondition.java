package io.crewscope.domain.action;

import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.RepositoryKey;
import java.util.Objects;

/** Internal repository, coding target and immutable baseline/delivery facts. */
public record ActionTargetPrecondition(
        RepositoryBindingId repositoryBindingId,
        long repositoryBindingVersion,
        RepositoryKey repositoryKey,
        RepositoryBranchName defaultBranch,
        CodingTargetSnapshotReference codingTarget,
        RepositoryCommitId baselineCommit,
        RepositoryCommitId deliveryCommit) {

    public ActionTargetPrecondition {
        repositoryBindingId = Objects.requireNonNull(repositoryBindingId, "repositoryBindingId");
        if (repositoryBindingVersion < 0) {
            throw new IllegalArgumentException("RepositoryBinding version must not be negative");
        }
        repositoryKey = Objects.requireNonNull(repositoryKey, "repositoryKey");
        defaultBranch = Objects.requireNonNull(defaultBranch, "defaultBranch");
        codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        baselineCommit = Objects.requireNonNull(baselineCommit, "baselineCommit");
        deliveryCommit = Objects.requireNonNull(deliveryCommit, "deliveryCommit");
    }
}
