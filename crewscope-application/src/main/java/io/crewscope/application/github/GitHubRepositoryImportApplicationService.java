package io.crewscope.application.github;

import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
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
        RepositoryKey repositoryKey = effectiveRepositoryKey(request, authorized.catalog().fullName());
        GitHubRepositoryImportJob existing = jobs.findActiveByTarget(
                        organizationId,
                        teamId,
                        projectId,
                        request.externalRepositoryId(),
                        repositoryKey)
                .orElse(null);
        if (existing != null
                && existing.status() != GitHubRepositoryImportStatus.FAILED
                && existing.status() != GitHubRepositoryImportStatus.CANCELLED) {
            return existing;
        }
        GitHubRepositoryImportJob keyOwner = jobs.findByRepositoryKey(repositoryKey)
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
                repositoryKey,
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

    private RepositoryKey effectiveRepositoryKey(
            CreateGitHubRepositoryImportCommand request, String fullName) {
        if (request.repositoryKey() != null) {
            return request.repositoryKey();
        }
        String base = fullName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "repository";
        }
        base = base.substring(0, Math.min(base.length(), 63));
        RepositoryKey candidate = new RepositoryKey(base);
        Optional<GitHubRepositoryImportJob> owner = jobs.findByRepositoryKey(candidate);
        if (owner.isEmpty()
                || owner.orElseThrow().externalRepositoryId().equals(request.externalRepositoryId())) {
            return candidate;
        }
        String suffix = shortHash(request.externalRepositoryId());
        String prefixed = base.substring(0, Math.min(base.length(), 54)) + "-" + suffix;
        return new RepositoryKey(prefixed);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(8);
            for (int index = 0; index < 4; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
