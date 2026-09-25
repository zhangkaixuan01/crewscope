package io.crewscope.application.responsibility;

import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItem;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItemId;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJob;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/**
 * Persistence Port for responsibility handover jobs and their queued items. The job row lock
 * serializes process/cancel against one job; item updates commit per item in their own short
 * transaction, so an interrupted run resumes at the first PENDING item.
 */
public interface ResponsibilityHandoverRepository {

    /** Inserts one job with its full item list; the command key makes replays return the original. */
    ResponsibilityHandoverJob createJob(
            ResponsibilityHandoverJob job, List<ResponsibilityHandoverItem> items);

    /** Locks and returns the job row (FOR UPDATE) for process/cancel serialization. */
    Optional<ResponsibilityHandoverJob> lockJobById(
            OrganizationId organizationId, ResponsibilityHandoverJobId jobId);

    /** Returns the job without locking for read paths. */
    Optional<ResponsibilityHandoverJob> findJobById(
            OrganizationId organizationId, ResponsibilityHandoverJobId jobId);

    /** Returns the one job queued under a command key for idempotent create replays. */
    Optional<ResponsibilityHandoverJob> findJobByCommandId(
            OrganizationId organizationId, String commandId);

    /** Commits one job status transition using the previous version as the lock predicate. */
    ResponsibilityHandoverJob updateJob(ResponsibilityHandoverJob job);

    /** Returns every item of one job in stable processing order. */
    List<ResponsibilityHandoverItem> findItems(
            OrganizationId organizationId, ResponsibilityHandoverJobId jobId);

    /** Locks and returns one item row (FOR UPDATE); processing starts only from PENDING. */
    Optional<ResponsibilityHandoverItem> lockItemById(
            OrganizationId organizationId, ResponsibilityHandoverItemId itemId);

    /** Commits one item outcome using the previous version as the lock predicate. */
    ResponsibilityHandoverItem updateItem(ResponsibilityHandoverItem item);
}
