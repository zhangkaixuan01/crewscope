package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewFinding;
import io.crewscope.domain.review.ReviewFindingFingerprint;
import io.crewscope.domain.review.ReviewFindingId;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped port enforcing one first Finding per Request and server fingerprint. */
public interface ReviewFindingRepository {

    Optional<ReviewFinding> findById(OrganizationId organizationId, ReviewFindingId id);

    Optional<ReviewFinding> findByRequestAndFingerprint(
            OrganizationId organizationId,
            ReviewRequestId reviewRequestId, ReviewFindingFingerprint fingerprint);

    List<ReviewFinding> findAllByRequest(
            OrganizationId organizationId, ReviewRequestId reviewRequestId);

    void insert(ReviewFinding finding);

    /** Atomically returns the first committed Finding when this fingerprint already exists. */
    default ReviewFinding insertOrFind(ReviewFinding finding) {
        insert(finding);
        return finding;
    }
}
