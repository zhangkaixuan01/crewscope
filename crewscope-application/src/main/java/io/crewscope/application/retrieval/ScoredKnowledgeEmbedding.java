package io.crewscope.application.retrieval;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import java.util.Objects;
import java.util.regex.Pattern;

/** One nearest-neighbor hit: the entry revision, its pinned content hash and similarity. */
public record ScoredKnowledgeEmbedding(
        KnowledgeEntryId entryId,
        KnowledgeEntryRevision revision,
        String contentHash,
        double score) {

    private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");

    public ScoredKnowledgeEmbedding {
        entryId = Objects.requireNonNull(entryId, "entryId");
        revision = Objects.requireNonNull(revision, "revision");
        if (contentHash == null || !CONTENT_HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException(
                    "contentHash must be a 64-character hex hash");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
    }
}
