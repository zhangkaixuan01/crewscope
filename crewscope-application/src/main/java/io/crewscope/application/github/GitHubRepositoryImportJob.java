package io.crewscope.application.github;

import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Path-free durable fact describing a GitHub Catalog item imported into a WorkProject. */
public record GitHubRepositoryImportJob(
        UUID id,
        OrganizationId organizationId,
        TeamId teamId,
        WorkProjectId projectId,
        ConnectionId connectionId,
        long connectionVersion,
        ConnectionGrantId grantId,
        long grantVersion,
        String externalRepositoryId,
        String repositoryFullName,
        RepositoryKey repositoryKey,
        RepositoryBranchName defaultBranch,
        GitHubRepositoryImportStatus status,
        int progressPercent,
        int attempt,
        Optional<String> failureCode,
        Optional<RepositoryBindingId> bindingId,
        PrincipalId createdBy,
        boolean createdByPlatformAdministrator,
        UtcTimestamp createdAt,
        UtcTimestamp updatedAt) {

    public GitHubRepositoryImportJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(grantId, "grantId");
        if (connectionVersion < 0 || grantVersion < 0 || attempt < 0) {
            throw new IllegalArgumentException("Import versions and attempt must not be negative");
        }
        externalRepositoryId = requireText(externalRepositoryId, 100, "externalRepositoryId");
        repositoryFullName = requireText(repositoryFullName, 511, "repositoryFullName");
        Objects.requireNonNull(repositoryKey, "repositoryKey");
        Objects.requireNonNull(defaultBranch, "defaultBranch");
        Objects.requireNonNull(status, "status");
        if (progressPercent < 0 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be between 0 and 100");
        }
        failureCode = Objects.requireNonNull(failureCode, "failureCode")
                .map(value -> requireText(value, 80, "failureCode"));
        bindingId = Objects.requireNonNull(bindingId, "bindingId");
        boolean ready = status == GitHubRepositoryImportStatus.READY;
        boolean failed = status == GitHubRepositoryImportStatus.FAILED
                || status == GitHubRepositoryImportStatus.CANCELLED;
        if (ready != bindingId.isPresent()) {
            throw new IllegalArgumentException("Only a READY import must contain a Binding ID");
        }
        if (failed != failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a FAILED or CANCELLED import must contain a failure code");
        }
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.compareTo(createdAt) < 0) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    public static GitHubRepositoryImportJob requested(
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            ConnectionId connectionId,
            long connectionVersion,
            ConnectionGrantId grantId,
            long grantVersion,
            String externalRepositoryId,
            String repositoryFullName,
            RepositoryKey repositoryKey,
            RepositoryBranchName defaultBranch,
            PrincipalId createdBy,
            boolean createdByPlatformAdministrator,
            UtcTimestamp now) {
        return new GitHubRepositoryImportJob(
                UUID.randomUUID(), organizationId, teamId, projectId, connectionId,
                connectionVersion, grantId, grantVersion, externalRepositoryId,
                repositoryFullName, repositoryKey, defaultBranch,
                GitHubRepositoryImportStatus.REQUESTED, 0, 0, Optional.empty(),
                Optional.empty(), createdBy, createdByPlatformAdministrator, now, now);
    }

    public GitHubRepositoryImportJob progress(
            GitHubRepositoryImportStatus next, int percent, Optional<String> failure,
            Optional<RepositoryBindingId> binding, int nextAttempt, UtcTimestamp now) {
        return new GitHubRepositoryImportJob(
                id, organizationId, teamId, projectId, connectionId, connectionVersion,
                grantId, grantVersion, externalRepositoryId, repositoryFullName, repositoryKey,
                defaultBranch, next, percent, nextAttempt, failure, binding, createdBy,
                createdByPlatformAdministrator, createdAt, now);
    }

    /** Requeues a terminal job with the newly authorized immutable request coordinates. */
    public GitHubRepositoryImportJob resubmit(
            ConnectionId nextConnectionId,
            long nextConnectionVersion,
            ConnectionGrantId nextGrantId,
            long nextGrantVersion,
            String nextRepositoryFullName,
            RepositoryBranchName nextDefaultBranch,
            PrincipalId requestedBy,
            boolean requestedByPlatformAdministrator,
            UtcTimestamp now) {
        return new GitHubRepositoryImportJob(
                id, organizationId, teamId, projectId, nextConnectionId, nextConnectionVersion,
                nextGrantId, nextGrantVersion, externalRepositoryId, nextRepositoryFullName,
                repositoryKey, nextDefaultBranch, GitHubRepositoryImportStatus.REQUESTED, 0,
                attempt, Optional.empty(), Optional.empty(), requestedBy,
                requestedByPlatformAdministrator, createdAt, now);
    }

    private static String requireText(String value, int max, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
