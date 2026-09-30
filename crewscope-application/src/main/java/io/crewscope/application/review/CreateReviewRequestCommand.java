package io.crewscope.application.review;

import io.crewscope.domain.task.PolicySnapshotId;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-owned Reviewer PolicySnapshot selected for a new exact ReviewRequest. An empty selection
 * lets the platform resolve the advisory Reviewer Agent automatically (M9b-Q02 review-gate path);
 * an explicit snapshot stays authoritative when one was pinned by the caller.
 */
public record CreateReviewRequestCommand(Optional<PolicySnapshotId> reviewerPolicySnapshotId) {

    public CreateReviewRequestCommand {
        reviewerPolicySnapshotId = Objects.requireNonNull(
                reviewerPolicySnapshotId, "reviewerPolicySnapshotId");
    }

    public CreateReviewRequestCommand(PolicySnapshotId explicitSnapshot) {
        this(Optional.of(Objects.requireNonNull(explicitSnapshot, "explicitSnapshot")));
    }
}
