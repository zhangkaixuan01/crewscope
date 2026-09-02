package io.crewscope.application.github;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Validates an import request and records durable work for the isolated Worker. */
public final class GitHubRepositoryImportApplicationService {

    private final GitHubRepositoryImportJobRepository jobs;
    private final GitHubRepositoryImportAuthorizationService authorization;
    private final RepositoryBindingAccessPolicy accessPolicy;
    private final TimeProvider timeProvider;

    public GitHubRepositoryImportApplicationService(
            GitHubRepositoryImportJobRepository jobs,
            GitHubRepositoryImportAuthorizationService authorization,
            RepositoryBindingAccessPolicy accessPolicy,
            TimeProvider timeProvider) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Persists REQUESTED work without touching Worker-owned repository storage. */
    public GitHubRepositoryImportJob create(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            CreateGitHubRepositoryImportCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CreateGitHubRepositoryImportCommand request = Objects.requireNonNull(command, "command");
        GitHubRepositoryImportAuthorization authorized = authorization.authorize(
                trusted.access(),
                trusted.correlationId(),
                organizationId,
                teamId,
                projectId,
                request.connectionId(),
                request.connectionVersion(),
                request.grantId(),
                request.grantVersion(),
                request.externalRepositoryId());
        GitHubRepositoryImportJob existing = jobs.findActiveByTarget(
                        organizationId,
                        teamId,
                        projectId,
                        request.externalRepositoryId(),
                        request.repositoryKey())
                .orElse(null);
        if (existing != null
                && existing.status() != GitHubRepositoryImportStatus.FAILED
                && existing.status() != GitHubRepositoryImportStatus.CANCELLED) {
            return existing;
        }
        GitHubRepositoryImportJob keyOwner = jobs.findByRepositoryKey(request.repositoryKey())
                .orElse(null);
        if (keyOwner != null && (existing == null || !keyOwner.id().equals(existing.id()))) {
            throw new GitHubProviderException(
                    GitHubProviderErrorCode.CONFLICT,
                    "Repository Key is already managed; bind the existing repository or choose another key");
        }
        UtcTimestamp now = timeProvider.now();
        if (existing != null) {
            return jobs.update(existing.resubmit(
                    request.connectionId(),
                    request.connectionVersion(),
                    request.grantId(),
                    request.grantVersion(),
                    authorized.catalog().fullName(),
                    request.defaultBranch(),
                    trusted.access().actor().id(),
                    trusted.access().platformAdministrator(),
                    now));
        }
        return jobs.create(GitHubRepositoryImportJob.requested(
                organizationId,
                teamId,
                projectId,
                request.connectionId(),
                request.connectionVersion(),
                request.grantId(),
                request.grantVersion(),
                authorized.catalog().externalRepositoryId(),
                authorized.catalog().fullName(),
                request.repositoryKey(),
                request.defaultBranch(),
                trusted.access().actor().id(),
                trusted.access().platformAdministrator(),
                now));
    }

    public GitHubRepositoryImportJob get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            UUID jobId) {
        accessPolicy.requireVisibleProject(context, organizationId, teamId, projectId);
        return jobs.findById(organizationId, teamId, projectId, jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "GitHub repository import job is unavailable"));
    }

    public GitHubRepositoryImportJob cancel(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            UUID jobId) {
        GitHubRepositoryImportJob job = get(
                context.access(), organizationId, teamId, projectId, jobId);
        accessPolicy.requireAdministrator(
                context.access(), organizationId, teamId, projectId, timeProvider.now());
        if (job.status() == GitHubRepositoryImportStatus.READY
                || job.status() == GitHubRepositoryImportStatus.CANCELLED
                || job.status() == GitHubRepositoryImportStatus.FAILED) {
            return job;
        }
        if (job.status() == GitHubRepositoryImportStatus.IMPORTING) {
            throw importAlreadyStarted();
        }
        Optional<GitHubRepositoryImportJob> cancelled =
                jobs.cancelBeforeImport(job, timeProvider.now());
        if (cancelled.isPresent()) {
            return cancelled.orElseThrow();
        }
        GitHubRepositoryImportJob latest = get(
                context.access(), organizationId, teamId, projectId, jobId);
        if (latest.status() == GitHubRepositoryImportStatus.READY
                || latest.status() == GitHubRepositoryImportStatus.CANCELLED
                || latest.status() == GitHubRepositoryImportStatus.FAILED) {
            return latest;
        }
        throw importAlreadyStarted();
    }

    public GitHubRepositoryImportJob retry(
            TeamCommandContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            UUID jobId) {
        GitHubRepositoryImportJob job = get(
                context.access(), organizationId, teamId, projectId, jobId);
        accessPolicy.requireAdministrator(
                context.access(), organizationId, teamId, projectId, timeProvider.now());
        if (job.status() != GitHubRepositoryImportStatus.FAILED) {
            return job;
        }
        GitHubRepositoryImportAuthorization authorized = authorization.authorize(
                context.access(),
                context.correlationId(),
                organizationId,
                teamId,
                projectId,
                job.connectionId(),
                job.connectionVersion(),
                job.grantId(),
                job.grantVersion(),
                job.externalRepositoryId());
        return jobs.update(job.resubmit(
                job.connectionId(),
                job.connectionVersion(),
                job.grantId(),
                job.grantVersion(),
                authorized.catalog().fullName(),
                job.defaultBranch(),
                context.access().actor().id(),
                context.access().platformAdministrator(),
                timeProvider.now()));
    }

    private static GitHubProviderException importAlreadyStarted() {
        return new GitHubProviderException(
                GitHubProviderErrorCode.CONFLICT,
                "Repository import has started and can no longer be cancelled");
    }
}
