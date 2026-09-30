package io.crewscope.application.github;

import java.util.Objects;
import java.util.UUID;

/**
 * Raised when a GitHub repository import job lookup misses within the caller's project scope.
 *
 * <p>Carries only the job id: the message is safe for application and audit boundaries, and the
 * server adapter maps it to the stable {@code 404 github_import_job_not_found} contract instead of
 * the generic aggregate code, so clients can tell a mistyped job id from other missing aggregates.
 */
public final class GitHubRepositoryImportJobNotFoundException extends RuntimeException {

    private final UUID jobId;

    public GitHubRepositoryImportJobNotFoundException(UUID jobId) {
        super("GitHub repository import job " + Objects.requireNonNull(jobId, "jobId")
                + " was not found");
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
