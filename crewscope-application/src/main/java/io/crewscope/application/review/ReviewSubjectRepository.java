package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Tenant-scoped persistence port for immutable Review subjects. */
public interface ReviewSubjectRepository {

    Optional<ReviewSubject> findById(OrganizationId organizationId, ReviewSubjectId id);

    void save(ReviewSubject subject);
}
