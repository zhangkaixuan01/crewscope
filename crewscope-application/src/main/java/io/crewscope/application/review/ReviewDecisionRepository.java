package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped append-only member Gate Decision persistence port. */
public interface ReviewDecisionRepository {

    Optional<ReviewDecision> findById(OrganizationId organizationId, ReviewDecisionId id);

    Optional<ReviewDecision> findLatestByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId);

    List<ReviewDecision> findDecisionsByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId);

    void insert(ReviewDecision decision);
}
