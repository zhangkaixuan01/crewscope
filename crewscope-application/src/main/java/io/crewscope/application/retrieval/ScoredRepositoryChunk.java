package io.crewscope.application.retrieval;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One repository chunk nearest-neighbor hit: the exact source span (path + 1-based
 * inclusive line range), the persisted content and its hash, and the cosine similarity.
 * The content column is deliberately redundant on the vector row (V3) so retrieval
 * never re-reads git or the artifact store — candidates assemble from this shape alone.
 */
public record ScoredRepositoryChunk(
        int chunkSeq,
        String path,
        String language,
        int startLine,
        int endLine,
        String contentHash,
        String content,
        double score) {

    private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");

    public ScoredRepositoryChunk {
        if (chunkSeq < 1) {
            throw new DomainValidationException(
                    "scoredRepositoryChunk.chunkSeq", "must be positive");
        }
        path = requirePath(path);
        language = Objects.requireNonNull(language, "language").strip();
        if (language.isEmpty() || language.length() > 40) {
            throw new DomainValidationException(
                    "scoredRepositoryChunk.language", "must contain 1 to 40 characters");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new DomainValidationException(
                    "scoredRepositoryChunk.lineSpan", "must satisfy 1 <= startLine <= endLine");
        }
        if (contentHash == null || !CONTENT_HASH.matcher(contentHash).matches()) {
            throw new DomainValidationException(
                    "scoredRepositoryChunk.contentHash", "must be a 64-character hex hash");
        }
        if (content == null || content.isEmpty()) {
            throw new DomainValidationException(
                    "scoredRepositoryChunk.content", "must not be empty");
        }
        if (!Double.isFinite(score)) {
            throw new DomainValidationException("scoredRepositoryChunk.score", "must be finite");
        }
    }

    private static String requirePath(String value) {
        String normalized = Objects.requireNonNull(value, "path").strip();
        if (normalized.isEmpty() || normalized.length() > 1024) {
            throw new DomainValidationException(
                    "scoredRepositoryChunk.path", "must contain 1 to 1024 characters");
        }
        return normalized;
    }
}
