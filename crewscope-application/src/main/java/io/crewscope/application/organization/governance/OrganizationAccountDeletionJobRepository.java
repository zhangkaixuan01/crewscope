package io.crewscope.application.organization.governance;

import io.crewscope.domain.organization.governance.AccountDeletionItem;
import io.crewscope.domain.organization.governance.AccountDeletionItemId;
import io.crewscope.domain.organization.governance.AccountDeletionJob;
import io.crewscope.domain.organization.governance.AccountDeletionJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;
import java.util.Optional;

/**
 * Persistence Port for account deletion jobs and their pinned coordination items. The job row
 * lock serializes confirm/process/cancel against one job; item updates commit per step in their
 * own short transaction, so a restored-backup re-run converges without rolling anything back.
 */
public interface OrganizationAccountDeletionJobRepository {

    /** Inserts one job with its full item list; the command key makes replays return the original. */
    AccountDeletionJob createJob(AccountDeletionJob job, List<AccountDeletionItem> items);

    /** Locks and returns the job row (FOR UPDATE) for confirm/process/cancel serialization. */
    Optional<AccountDeletionJob> lockJobById(
            OrganizationId organizationId, AccountDeletionJobId jobId);

    /** Returns the job without locking for read paths. */
    Optional<AccountDeletionJob> findJobById(
            OrganizationId organizationId, AccountDeletionJobId jobId);

    /** Returns the one job queued under a command key for idempotent create replays. */
    Optional<AccountDeletionJob> findJobByCommandId(OrganizationId organizationId, String commandId);

    /** Commits one job status transition using the previous version as the lock predicate. */
    AccountDeletionJob updateJob(AccountDeletionJob job);

    /** Returns every item of one job in stable processing order — the reconciliation checklist. */
    List<AccountDeletionItem> findItems(
            OrganizationId organizationId, AccountDeletionJobId jobId);

    /** Locks and returns one item row (FOR UPDATE); processing starts only from PENDING. */
    Optional<AccountDeletionItem> lockItemById(
            OrganizationId organizationId, AccountDeletionItemId itemId);

    /** Commits one item outcome using the previous version as the lock predicate. */
    AccountDeletionItem updateItem(AccountDeletionItem item);
}
