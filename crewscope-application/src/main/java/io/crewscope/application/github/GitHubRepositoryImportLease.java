package io.crewscope.application.github;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Fenced claim of one durable import job by a single Worker instance. */
public record GitHubRepositoryImportLease(
        GitHubRepositoryImportJob job, String owner, UtcTimestamp expiresAt) {

    public GitHubRepositoryImportLease {
        job = Objects.requireNonNull(job, "job");
        owner = requireOwner(owner);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static String requireOwner(String value) {
        String normalized = Objects.requireNonNull(value, "owner").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("Import lease owner must contain 1 to 160 characters");
        }
        return normalized;
    }
}
