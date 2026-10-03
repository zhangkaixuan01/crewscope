package io.crewscope.application.retrieval;

import java.util.Objects;
import java.util.UUID;

/**
 * The knowledge-index job can no longer be cancelled (M10-I01c): it was already
 * claimed for execution, or it reached a terminal state other than CANCELLED.
 * Cancelling an already-CANCELLED job stays idempotent and never throws this.
 */
public final class KnowledgeIndexJobNotCancellableException extends RuntimeException {

    private final UUID jobId;
    private final KnowledgeIndexJobStatus status;

    public KnowledgeIndexJobNotCancellableException(UUID jobId, KnowledgeIndexJobStatus status) {
        super("knowledge index job " + Objects.requireNonNull(jobId, "jobId")
                + " is " + Objects.requireNonNull(status, "status")
                + " and cannot be cancelled");
        this.jobId = jobId;
        this.status = status;
    }

    public UUID jobId() {
        return jobId;
    }

    public KnowledgeIndexJobStatus status() {
        return status;
    }
}
