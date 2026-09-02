package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Durable persistence port for import jobs, scoped by Organization, Team and WorkProject. */
public interface GitHubRepositoryImportJobRepository {
    GitHubRepositoryImportJob create(GitHubRepositoryImportJob job);
    GitHubRepositoryImportJob update(GitHubRepositoryImportJob job);
    Optional<GitHubRepositoryImportJob> findById(OrganizationId organizationId, TeamId teamId,
            WorkProjectId projectId, UUID jobId);
    Optional<GitHubRepositoryImportJob> findActiveByTarget(OrganizationId organizationId, TeamId teamId,
            WorkProjectId projectId, String externalRepositoryId, RepositoryKey repositoryKey);
    Optional<GitHubRepositoryImportJob> findByRepositoryKey(RepositoryKey repositoryKey);

    /** Atomically cancels work only before repository I/O has started. */
    Optional<GitHubRepositoryImportJob> cancelBeforeImport(
            GitHubRepositoryImportJob job, UtcTimestamp cancelledAt);

    /** Atomically claims the oldest requested or expired in-flight job. */
    Optional<GitHubRepositoryImportLease> claimNext(
            String leaseOwner, UtcTimestamp now, Duration leaseDuration);

    /** Commits progress only while the caller still owns the unexpired lease. */
    Optional<GitHubRepositoryImportJob> updateClaimed(
            GitHubRepositoryImportJob job,
            String leaseOwner,
            UtcTimestamp now,
            Duration leaseDuration);
}
