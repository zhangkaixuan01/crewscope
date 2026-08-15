package io.crewscope.application.task;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.ExecutionLease;
import io.crewscope.domain.task.ExecutionLeaseId;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for the single active TaskExecution Lease; no Step Lease Port exists. */
public interface ExecutionLeaseRepository {

    /** Atomically commits READY-to-CLAIMED and its single active PREPARE Lease. */
    ExecutionLease acquire(TaskExecution claimedExecution, ExecutionLease lease);

    /** Persists a Heartbeat using Lease ownership and Lease Version predicates. */
    ExecutionLease renew(ExecutionLease lease);

    /** Persists the PREPARE-to-RUN boundary using Lease and TaskExecution predicates. */
    ExecutionLease switchPhase(TaskExecution runningExecution, ExecutionLease runLease);

    /**
     * Commits a non-terminal TaskExecution mutation only while the supplied Lease still owns it.
     * Heartbeat may advance Lease Version concurrently, so ownership is fenced by immutable
     * coordinates and authoritative expiry rather than by Lease Version.
     */
    TaskExecution updateOwned(
            TaskExecution execution, ExecutionLease activeLease, UtcTimestamp authoritativeNow);

    /** Atomically commits a TaskExecution outcome and its terminal Lease release fact. */
    ExecutionLease release(TaskExecution execution, ExecutionLease releasedLease);

    Optional<ExecutionLease> findById(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionLeaseId leaseId);

    /**
     * Locks one Lease for a larger ownership-sensitive transaction.
     *
     * <p>The caller must keep an outer transaction open while it validates authoritative time and
     * commits the protected facts. Locking prevents Heartbeat, release and expiry recovery from
     * crossing that validation boundary.
     */
    Optional<ExecutionLease> findByIdForUpdate(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            ExecutionLeaseId leaseId);

    Optional<ExecutionLease> findActiveByTaskExecution(
            OrganizationId organizationId, TaskExecutionId taskExecutionId);

    /**
     * Locks an expired batch with {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p>The caller must keep an outer Sweeper transaction open through authoritative-time
     * revalidation and the corresponding {@link #release(TaskExecution, ExecutionLease)} call.
     */
    List<ExecutionLease> findExpired(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            UtcTimestamp authoritativeNow,
            int limit);
}
