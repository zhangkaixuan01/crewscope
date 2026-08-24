package io.crewscope.application.review;

import io.crewscope.domain.task.PolicySnapshotId;
import java.util.Objects;

/** Server-owned Reviewer PolicySnapshot selected for a new exact ReviewRequest. */
public record CreateReviewRequestCommand(PolicySnapshotId reviewerPolicySnapshotId) {

    public CreateReviewRequestCommand {
        reviewerPolicySnapshotId = Objects.requireNonNull(
                reviewerPolicySnapshotId, "reviewerPolicySnapshotId");
    }
}
