package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;
import java.util.Optional;

/** Query-only observation of the current remote GitHub branch Head. */
public record GitHubBranchQueryResult(
        Optional<RepositoryCommitId> remoteHead, UtcTimestamp observedAt) {

    public GitHubBranchQueryResult {
        remoteHead = Objects.requireNonNull(remoteHead, "remoteHead");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }
}
