package io.crewscope.application.retrieval;

import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One stored repository chunk embedding: the exact source span (path + 1-based inclusive
 * line range), the content and its SHA-256 hash, and the exact model revision that
 * produced the vector. Rows are positioned by (generation, chunkSeq) and written through
 * idempotent upserts, so a resumed build re-embeds a batch at worst, never duplicates it.
 */
public record RepositoryChunkVector(
        RepositoryGenerationKey generation,
        int chunkSeq,
        String path,
        String language,
        int startLine,
        int endLine,
        String contentHash,
        String content,
        EmbeddingModelRevision model,
        float[] embedding) {

    private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");

    public RepositoryChunkVector {
        Objects.requireNonNull(generation, "generation");
        if (chunkSeq < 1) {
            throw new DomainValidationException(
                    "repositoryChunk.chunkSeq", "must be positive");
        }
        path = requirePath(path);
        language = Objects.requireNonNull(language, "language").strip();
        if (language.isEmpty() || language.length() > 40) {
            throw new DomainValidationException(
                    "repositoryChunk.language", "must contain 1 to 40 characters");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new DomainValidationException(
                    "repositoryChunk.lineSpan", "must satisfy 1 <= startLine <= endLine");
        }
        if (contentHash == null || !CONTENT_HASH.matcher(contentHash).matches()) {
            throw new DomainValidationException(
                    "repositoryChunk.contentHash", "must be a 64-character hex hash");
        }
        if (content == null || content.isEmpty()) {
            throw new DomainValidationException(
                    "repositoryChunk.content", "must not be empty");
        }
        Objects.requireNonNull(model, "model");
        if (embedding == null || embedding.length != model.dimension()) {
            throw new DomainValidationException(
                    "repositoryChunk.embedding",
                    "must carry exactly the model revision dimension");
        }
        embedding = embedding.clone();
        for (float component : embedding) {
            if (!Float.isFinite(component)) {
                throw new DomainValidationException(
                        "repositoryChunk.embedding", "must contain only finite components");
            }
        }
    }

    /** Defensive copy: the stored geometry never aliases a caller-owned buffer. */
    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    private static String requirePath(String value) {
        String normalized = Objects.requireNonNull(value, "path").strip();
        if (normalized.isEmpty() || normalized.length() > 1024) {
            throw new DomainValidationException(
                    "repositoryChunk.path", "must contain 1 to 1024 characters");
        }
        return normalized;
    }
}
