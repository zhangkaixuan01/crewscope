package io.crewscope.application.retrieval;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;

/**
 * Persistence port for the I02c reference evidence on top of the sealed manifests.
 * Both sides are structurally idempotent: feedback replays the stored row for the
 * same (execution, source key, member), and a claimed receipt with the same set for
 * the same (execution, attempt) replays while a different set is a conflict.
 */
public interface InjectionReferenceRepository {

    /** Stores the feedback or converges on the already-stored row (never a duplicate). */
    InjectionReferenceFeedback record(InjectionReferenceFeedback feedback);

    /** The member's own feedback rows for one execution, ascending by creation. */
    List<InjectionReferenceFeedback> findFeedback(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId executionId,
            PrincipalId memberPrincipalId);

    /** Stores the receipt, replays an identical set, conflicts on a different set. */
    InjectionClaimedReferences recordClaimed(InjectionClaimedReferences receipt);

    /** Every claimed receipt of one execution, ascending by attempt. */
    List<InjectionClaimedReferences> findClaimed(
            OrganizationId organizationId,
            TeamId teamId,
            TaskExecutionId executionId);
}
