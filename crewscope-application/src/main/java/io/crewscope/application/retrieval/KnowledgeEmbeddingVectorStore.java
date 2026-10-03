package io.crewscope.application.retrieval;

import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;

/**
 * Persistence Port for knowledge entry embedding vectors (M10-I01a, §10.2 pgvector).
 *
 * <p>Positioning is uniquely keyed by (organization, team, entry, revision, model key,
 * dimension) — one row per source revision per model slot. {@link #replace} is an idempotent
 * upsert: a changed {@code model revision} of the same slot overwrites the previous row, so
 * stale vectors of a superseded model revision are never mixed into a new search. The
 * adapter hardcodes the tenant predicates and the effective published-revision gate ahead
 * of the vector top-K, and {@link KnowledgeEmbeddingQuery} is closed — no caller can widen
 * the scanned set beyond one Team's published knowledge.
 */
public interface KnowledgeEmbeddingVectorStore {

    /** Idempotently stores one vector; an existing row at the same position is replaced. */
    void replace(KnowledgeEmbeddingVector vector);

    /** Removes every model slot of one entry (all revisions); returns the removed rows. */
    int deleteByEntry(OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId);

    /**
     * Whether one entry already carries a committed vector row for the given revision —
     * the same authority the status projection reads, so a resumed job and the reported
     * {@code INDEXED} can never disagree about what is embedded.
     */
    boolean isEmbedded(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision);

    /** Bounded nearest neighbors within one Team's published, in-force knowledge. */
    List<ScoredKnowledgeEmbedding> nearest(KnowledgeEmbeddingQuery query);
}
