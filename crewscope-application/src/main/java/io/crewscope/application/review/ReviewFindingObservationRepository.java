package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewFindingId;
import io.crewscope.domain.review.ReviewFindingObservation;
import io.crewscope.domain.review.ReviewFindingObservationId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped append-only port for later candidates merged into a first Finding. */
public interface ReviewFindingObservationRepository {

    Optional<ReviewFindingObservation> findById(
            OrganizationId organizationId, ReviewFindingObservationId id);

    List<ReviewFindingObservation> findAllByFinding(
            OrganizationId organizationId, ReviewFindingId findingId);

    void insert(ReviewFindingObservation observation);

    /** Serializes duplicate numbering per Finding and returns the committed observation. */
    default ReviewFindingObservation append(ReviewFindingObservation observation) {
        insert(observation);
        return observation;
    }
}
