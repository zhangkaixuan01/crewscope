package io.crewscope.application.review;

import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewModificationRoundId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.TaskId;
import java.util.List;
import java.util.Optional;

/** Tenant-scoped append-only port for continuous changes-requested rounds. */
public interface ReviewModificationRoundRepository {

    Optional<ReviewModificationRound> findById(
            OrganizationId organizationId, ReviewModificationRoundId id);

    Optional<ReviewModificationRound> findLatestByTask(
            OrganizationId organizationId, TaskId taskId);

    List<ReviewModificationRound> findAllByTask(
            OrganizationId organizationId, TaskId taskId);

    void insert(ReviewModificationRound round);
}
