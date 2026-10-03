package io.crewscope.application.retrieval;

/**
 * Closed failure-code vocabulary stored on {@code knowledge_index_job.failure_code}.
 * Embedding delivery failures carry the sanitized
 * {@link io.crewscope.domain.model.ModelConnectionHealthFailureCode} name instead of a
 * constant from here; both spellings share the bounded 80-character column.
 */
public final class KnowledgeIndexFailureCodes {

    /** One chunk exceeds {@link io.crewscope.application.embedding.EmbeddingClient#MAX_INPUT_CHARS}; never truncated. */
    public static final String CHUNK_TOO_LARGE = "CHUNK_TOO_LARGE";

    /** The embedding model drifted from the one frozen into the index key at enqueue time. */
    public static final String MODEL_DRIFT = "MODEL_DRIFT";

    /** The build exceeded the per-generation chunk budget. */
    public static final String CHUNK_LIMIT_EXCEEDED = "CHUNK_LIMIT_EXCEEDED";

    /** A repository read exceeded the Git command output ceiling; the repo is not paginated. */
    public static final String REPOSITORY_TOO_LARGE = "REPOSITORY_TOO_LARGE";

    /** The binding's managed repository could not be resolved or does not carry the commit. */
    public static final String REPOSITORY_UNAVAILABLE = "REPOSITORY_UNAVAILABLE";

    /** A repository content read failed for an unstated infrastructure reason. */
    public static final String REPOSITORY_READ_FAILED = "REPOSITORY_READ_FAILED";

    /** The activation CAS lost its race; the job may be re-enqueued. */
    public static final String GENERATION_CONFLICT = "GENERATION_CONFLICT";

    /** Reserved for unexpected worker faults; never carries provider detail. */
    public static final String INTERNAL = "INTERNAL";

    /** Cancellation of a still-QUEUED job. */
    public static final String CANCELLED = "CANCELLED";

    private KnowledgeIndexFailureCodes() {
    }
}
