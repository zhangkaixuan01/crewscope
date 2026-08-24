package io.crewscope.application.action;

import io.crewscope.domain.action.ExternalRepositoryId;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.review.ReviewDecisionId;
import java.util.Objects;
import java.util.Optional;

/** Member-reviewed inputs for a server-derived Push Branch and Draft PR action graph. */
public record PlanSourceDeliveryActionRequest(
        ReviewDecisionId reviewDecisionId,
        ProviderBindingId providerBindingId,
        ExternalRepositoryId externalRepositoryId,
        Optional<RepositoryCommitId> expectedRemoteHead,
        String title,
        String body) {

    public PlanSourceDeliveryActionRequest {
        reviewDecisionId = Objects.requireNonNull(reviewDecisionId, "reviewDecisionId");
        providerBindingId = Objects.requireNonNull(providerBindingId, "providerBindingId");
        externalRepositoryId = Objects.requireNonNull(externalRepositoryId, "externalRepositoryId");
        expectedRemoteHead = Objects.requireNonNull(expectedRemoteHead, "expectedRemoteHead");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Draft PR title must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Draft PR body must not be blank");
        }
        title = title.strip();
        body = body.strip();
    }
}
