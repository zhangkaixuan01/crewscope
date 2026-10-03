package io.crewscope.application.retrieval;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of one repository-build enqueue through the control plane (M10-I01c):
 * {@code accepted=false} means the refresh gate is closed — a skip, not an error —
 * and carries no job; an accepted enqueue carries the live job (created now or
 * collapsed onto an existing live job for the same index coordinate).
 */
public record RepositoryBuildEnqueueResult(
        boolean accepted, Optional<KnowledgeIndexJob> job) {

    public RepositoryBuildEnqueueResult {
        job = Objects.requireNonNull(job, "job");
        if (!accepted && job.isPresent()) {
            throw new IllegalArgumentException(
                    "A skipped repository build carries no job");
        }
    }

    public static RepositoryBuildEnqueueResult skipped() {
        return new RepositoryBuildEnqueueResult(false, Optional.empty());
    }

    public static RepositoryBuildEnqueueResult accepted(KnowledgeIndexJob job) {
        return new RepositoryBuildEnqueueResult(true, Optional.of(job));
    }
}
