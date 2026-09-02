package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import java.util.Objects;

/** Browser-safe import command. It deliberately contains no URL, token or host path. */
public record CreateGitHubRepositoryImportCommand(
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        String externalRepositoryId,
        RepositoryKey repositoryKey,
        RepositoryBranchName defaultBranch) {
    public CreateGitHubRepositoryImportCommand {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(grantId, "grantId");
        if (connectionVersion < 0 || grantVersion < 0) {
            throw new IllegalArgumentException("Connection versions must not be negative");
        }
        externalRepositoryId = Objects.requireNonNull(externalRepositoryId, "externalRepositoryId").strip();
        if (externalRepositoryId.isEmpty()) throw new IllegalArgumentException("externalRepositoryId is blank");
        Objects.requireNonNull(repositoryKey, "repositoryKey");
        Objects.requireNonNull(defaultBranch, "defaultBranch");
    }
}
