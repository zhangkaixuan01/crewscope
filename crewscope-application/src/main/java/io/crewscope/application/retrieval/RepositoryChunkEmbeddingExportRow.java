package io.crewscope.application.retrieval;

import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Objects;

/**
 * Read-only export projection of one repository chunk embedding row — the governance export
 * face. The stored index coordinate is a one-way hash of the full
 * {@link io.crewscope.domain.retrieval.RepositoryIndexKey}, so the projection carries the raw
 * columns (never a reconstructed domain key) plus the embedding as produced geometry.
 */
public record RepositoryChunkEmbeddingExportRow(
        String indexKey,
        long buildSequence,
        int chunkSeq,
        TeamId teamId,
        RepositoryBindingId repositoryBindingId,
        String path,
        String language,
        int startLine,
        int endLine,
        String contentHash,
        String modelKey,
        long modelRevision,
        int dimension,
        float[] embedding) {

    public RepositoryChunkEmbeddingExportRow {
        Objects.requireNonNull(indexKey, "indexKey");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(repositoryBindingId, "repositoryBindingId");
        path = Objects.requireNonNull(path, "path");
        language = Objects.requireNonNull(language, "language");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(modelKey, "modelKey");
        if (dimension <= 0) {
            throw new IllegalArgumentException("export row dimension must be positive");
        }
        embedding = Objects.requireNonNull(embedding, "embedding").clone();
        if (embedding.length != dimension) {
            throw new IllegalArgumentException("export row embedding dimension mismatch");
        }
    }

    /** Defensive copy: the stored geometry never aliases a caller-owned buffer. */
    @Override
    public float[] embedding() {
        return embedding.clone();
    }
}
