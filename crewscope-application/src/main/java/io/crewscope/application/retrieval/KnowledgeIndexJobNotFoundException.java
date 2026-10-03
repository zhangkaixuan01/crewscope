package io.crewscope.application.retrieval;

import java.util.Objects;
import java.util.UUID;

/**
 * The referenced knowledge-index job does not exist within the caller's Team
 * (M10-I01c). The not-found shape is identical for missing and cross-tenant ids:
 * neither leaks whether the id exists under another tenant.
 */
public final class KnowledgeIndexJobNotFoundException extends RuntimeException {

    private final UUID jobId;

    public KnowledgeIndexJobNotFoundException(UUID jobId) {
        super("knowledge index job " + Objects.requireNonNull(jobId, "jobId")
                + " was not found in this Team");
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
