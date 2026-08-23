package io.crewscope.domain.action;

import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ConnectionId;
import java.util.Objects;
import java.util.Optional;

/** Exact force-with-lease coordinates for publishing one delivery commit. */
public record PushBranchActionParameters(
        ExternalRepositoryId repositoryId,
        RepositoryBranchReference branch,
        RepositoryCommitId deliveryHead,
        Optional<RepositoryCommitId> expectedRemoteHead,
        ConnectionId connectionId) implements ActionParameters {

    public PushBranchActionParameters {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId");
        branch = Objects.requireNonNull(branch, "branch");
        deliveryHead = Objects.requireNonNull(deliveryHead, "deliveryHead");
        expectedRemoteHead = Objects.requireNonNull(expectedRemoteHead, "expectedRemoteHead");
        connectionId = Objects.requireNonNull(connectionId, "connectionId");
    }

    @Override
    public ActionKind kind() {
        return ActionKind.PUSH_BRANCH;
    }

    @Override
    public void appendCanonical(ActionCanonicalEncoder encoder) {
        encoder.add(repositoryId.value())
                .add(branch.value())
                .add(deliveryHead.value())
                .add(expectedRemoteHead.map(RepositoryCommitId::value).orElse("ABSENT"))
                .add(connectionId.toString());
    }
}
