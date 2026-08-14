package io.crewscope.application.task;

import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskCredentialGrant;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskTokenJtiHash;
import java.util.List;
import java.util.Optional;

/** Persistence Port for revocable, short-lived Task Token authorization grants. */
public interface TaskCredentialGrantRepository {

    /**
     * Creates one grant under a globally unique JTI Hash and a single-active-grant-per-execution
     * constraint.
     */
    TaskCredentialGrant create(TaskCredentialGrant grant);

    /** Records an authorized use with a Grant Version predicate. */
    TaskCredentialGrant recordUse(TaskCredentialGrant usedGrant);

    /** Commits REVOKED or EXPIRED using the previous Grant Version as predicate. */
    TaskCredentialGrant terminate(TaskCredentialGrant terminatedGrant);

    /** Atomically terminates the current grant and creates its scope-narrowed replacement. */
    TaskCredentialGrant rotate(
            TaskCredentialGrant terminatedCurrent, TaskCredentialGrant replacement);

    Optional<TaskCredentialGrant> findByJtiHash(
            OrganizationId organizationId, TaskTokenJtiHash jtiHash);

    Optional<TaskCredentialGrant> findActiveByTaskExecution(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            TaskExecutionId taskExecutionId);

    /**
     * Locks an expired batch with {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p>The caller must keep an outer Sweeper transaction open through authoritative-time
     * revalidation and the corresponding terminal update.
     */
    List<TaskCredentialGrant> findExpired(
            OrganizationId organizationId,
            RuntimeEnvironment environment,
            UtcTimestamp authoritativeNow,
            int limit);
}
