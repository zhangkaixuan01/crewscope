package io.crewscope.application.organization.governance;

import io.crewscope.domain.organization.governance.DataExportItem;
import io.crewscope.domain.organization.governance.DataExportItemId;
import io.crewscope.domain.organization.governance.DataExportJob;
import io.crewscope.domain.organization.governance.DataExportJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/**
 * Persistence Port for data export jobs, their pinned batch items and the frozen artifacts.
 * The job row lock serializes process/cancel against one job; item updates commit per item in
 * their own short transaction, so an interrupted run resumes at the first PENDING item.
 */
public interface OrganizationDataExportJobRepository {

    /** Inserts one job with its full item list; the command key makes replays return the original. */
    DataExportJob createJob(DataExportJob job, List<DataExportItem> items);

    /** Locks and returns the job row (FOR UPDATE) for process/cancel serialization. */
    Optional<DataExportJob> lockJobById(OrganizationId organizationId, DataExportJobId jobId);

    /** Returns the job without locking for read paths. */
    Optional<DataExportJob> findJobById(OrganizationId organizationId, DataExportJobId jobId);

    /** Returns the one job queued under a command key for idempotent create replays. */
    Optional<DataExportJob> findJobByCommandId(OrganizationId organizationId, String commandId);

    /** Commits one job status transition using the previous version as the lock predicate. */
    DataExportJob updateJob(DataExportJob job);

    /** Returns every item of one job in stable processing order. */
    List<DataExportItem> findItems(OrganizationId organizationId, DataExportJobId jobId);

    /** Locks and returns one item row (FOR UPDATE); processing starts only from PENDING. */
    Optional<DataExportItem> lockItemById(OrganizationId organizationId, DataExportItemId itemId);

    /** Commits one item outcome using the previous version as the lock predicate. */
    DataExportItem updateItem(DataExportItem item);

    /** Inserts one frozen artifact row produced by settling a batch item. */
    DataExportArtifact createArtifact(DataExportArtifact artifact);

    /** Returns every artifact of one job ordered by artifact sequence (the download face). */
    List<DataExportArtifact> findArtifacts(OrganizationId organizationId, DataExportJobId jobId);
}
