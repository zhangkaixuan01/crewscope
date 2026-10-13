package io.crewscope.infrastructure.persistence.knowledge;

import io.crewscope.application.retrieval.KnowledgeEmbeddingQuery;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVector;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.ScoredKnowledgeEmbedding;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.infrastructure.persistence.vector.VectorLiteral;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * pgvector adapter for the knowledge embedding Port (M10-I01a, S01 §3.4 frozen shape).
 * Vectors cross the wire as text literals bound through {@code CAST(? AS public.vector)},
 * so no PGvector Java dependency is needed. The tenant predicates and the authoritative
 * published-revision gate join ahead of the vector top-K: a search can never rank a row
 * whose entry head does not still point at that revision as effective and PUBLISHED.
 *
 * <p>This bean is assembled only behind {@code crewscope.knowledge.vector.enabled=true}
 * (see KnowledgeVectorConfiguration); the table itself lives on the independent
 * migration-vector Flyway chain.
 */
public final class PgVectorKnowledgeEmbeddingStore implements KnowledgeEmbeddingVectorStore {

    private final JdbcTemplate jdbc;

    public PgVectorKnowledgeEmbeddingStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void replace(KnowledgeEmbeddingVector vector) {
        KnowledgeEmbeddingVector value = Objects.requireNonNull(vector, "vector");
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry_embedding (
                    organization_id, team_id, entry_id, revision, model_key, dimension,
                    model_revision, content_hash, embedding, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS public.vector), now())
                ON CONFLICT (organization_id, team_id, entry_id, revision, model_key, dimension)
                DO UPDATE SET
                    model_revision = EXCLUDED.model_revision,
                    content_hash = EXCLUDED.content_hash,
                    embedding = EXCLUDED.embedding,
                    created_at = EXCLUDED.created_at
                """,
                value.organizationId().value(),
                value.teamId().value(),
                value.entryId().value(),
                value.revision().value(),
                value.model().modelKey(),
                value.model().dimension(),
                value.model().revision(),
                value.contentHash(),
                VectorLiteral.of(value.embedding()));
    }

    @Override
    public int deleteByEntry(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(entryId, "entryId");
        return jdbc.update(
                """
                DELETE FROM crewscope.knowledge_entry_embedding
                WHERE organization_id = ? AND team_id = ? AND entry_id = ?
                """,
                organizationId.value(), teamId.value(), entryId.value());
    }

    @Override
    public boolean isEmbedded(
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(revision, "revision");
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM crewscope.knowledge_entry_embedding
                    WHERE organization_id = ? AND team_id = ? AND entry_id = ? AND revision = ?
                )
                """,
                Boolean.class,
                organizationId.value(), teamId.value(), entryId.value(), revision.value()));
    }

    @Override
    public List<ScoredKnowledgeEmbedding> nearest(KnowledgeEmbeddingQuery query) {        KnowledgeEmbeddingQuery value = Objects.requireNonNull(query, "query");
        String literal = VectorLiteral.of(value.queryVector());
        return jdbc.query(
                """
                SELECT v.entry_id, v.revision, v.content_hash,
                       1 - (v.embedding <=> CAST(? AS public.vector)) AS similarity
                FROM crewscope.knowledge_entry_embedding v
                JOIN crewscope.knowledge_entry head
                  ON head.organization_id = v.organization_id
                 AND head.team_id = v.team_id
                 AND head.id = v.entry_id
                 AND head.status = 'PUBLISHED'
                 AND head.effective_revision = v.revision
                WHERE v.organization_id = ?
                  AND v.team_id = ?
                  AND v.model_key = ?
                  AND v.dimension = ?
                  AND v.model_revision = ?
                ORDER BY v.embedding <=> CAST(? AS public.vector)
                LIMIT ?
                """,
                (rs, row) -> new ScoredKnowledgeEmbedding(
                        new KnowledgeEntryId(rs.getObject("entry_id", java.util.UUID.class)),
                        new KnowledgeEntryRevision(rs.getLong("revision")),
                        rs.getString("content_hash"),
                        rs.getDouble("similarity")),
                literal,
                value.organizationId().value(),
                value.teamId().value(),
                value.model().modelKey(),
                value.model().dimension(),
                value.model().revision(),
                literal,
                value.topK());
    }

    @Override
    public boolean isEnabled() {
        // The vector chain is a separate Flyway history: probe the table instead of assuming it.
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('crewscope.knowledge_entry_embedding') IS NOT NULL",
                    Boolean.class));
        } catch (org.springframework.dao.DataAccessException unreachable) {
            return false;
        }
    }

    @Override
    public List<KnowledgeEmbeddingVector> pageByOrganization(
            OrganizationId organizationId, String afterCursor, int limit) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (limit <= 0) {
            return List.of();
        }
        // Keyset cursor "entryId|revision|modelKey|dimension" over the row order below.
        if (afterCursor == null || afterCursor.isBlank()) {
            return jdbc.query(
                    """
                    SELECT team_id, entry_id, revision, model_key, dimension, model_revision,
                           content_hash, embedding::text
                      FROM crewscope.knowledge_entry_embedding
                     WHERE organization_id = ?
                     ORDER BY entry_id, revision, model_key, dimension
                     LIMIT ?
                    """,
                    (rs, rowNum) -> rowOf(organizationId, rs),
                    organizationId.value(),
                    limit);
        }
        String[] cursor = afterCursor.split("\\|", 4);
        if (cursor.length != 4) {
            throw new IllegalArgumentException("Malformed embedding page cursor");
        }
        return jdbc.query(
                """
                SELECT team_id, entry_id, revision, model_key, dimension, model_revision,
                       content_hash, embedding::text
                  FROM crewscope.knowledge_entry_embedding
                 WHERE organization_id = ?
                   AND (entry_id, revision, model_key, dimension)
                       > (?::uuid, ?::bigint, ?, ?::int)
                 ORDER BY entry_id, revision, model_key, dimension
                 LIMIT ?
                """,
                (rs, rowNum) -> rowOf(organizationId, rs),
                organizationId.value(),
                cursor[0],
                Long.parseLong(cursor[1]),
                cursor[2],
                Integer.parseInt(cursor[3]),
                limit);
    }

    private static KnowledgeEmbeddingVector rowOf(
            OrganizationId organizationId, java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeEmbeddingVector(
                organizationId,
                new TeamId(rs.getObject("team_id", java.util.UUID.class)),
                new KnowledgeEntryId(rs.getObject("entry_id", java.util.UUID.class)),
                new KnowledgeEntryRevision(rs.getLong("revision")),
                new io.crewscope.domain.retrieval.EmbeddingModelRevision(
                        rs.getString("model_key"),
                        rs.getInt("dimension"),
                        rs.getLong("model_revision")),
                rs.getString("content_hash"),
                VectorLiteral.parse(rs.getString("embedding")));
    }
}
