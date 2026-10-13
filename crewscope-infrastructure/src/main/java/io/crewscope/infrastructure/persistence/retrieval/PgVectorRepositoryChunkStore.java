package io.crewscope.infrastructure.persistence.retrieval;

import io.crewscope.application.retrieval.RepositoryChunkEmbeddingExportRow;
import io.crewscope.application.retrieval.RepositoryChunkEmbeddingQuery;
import io.crewscope.application.retrieval.RepositoryChunkVector;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.application.retrieval.ScoredRepositoryChunk;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.infrastructure.persistence.vector.VectorLiteral;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * pgvector persistence for repository chunk embeddings (M10-I01b write side / M10-A01
 * read side, vector-chain V3). Vectors cross the wire as text literals bound through
 * {@code CAST(? AS public.vector)} like {@code PgVectorKnowledgeEmbeddingStore}; rows
 * upsert on (index_key, build_sequence, chunk_seq), so the crash-recovery re-embed of one
 * batch overwrites the same positions instead of duplicating them. Retention deletes flow
 * through the activation transaction in {@code JdbcRepositoryGenerationStoreAdapter}.
 * The nearest query hardcodes the tenant, binding and generation predicates ahead of the
 * vector ordering — the candidate set is one ACTIVE generation by construction.
 */
public final class PgVectorRepositoryChunkStore implements RepositoryChunkVectorStore {

    private final JdbcTemplate jdbc;

    public PgVectorRepositoryChunkStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void replaceBatch(RepositoryGenerationKey generation, List<RepositoryChunkVector> vectors) {
        RepositoryGenerationKey coordinate = Objects.requireNonNull(generation, "generation");
        List<RepositoryChunkVector> batch =
                List.copyOf(Objects.requireNonNull(vectors, "vectors"));
        for (RepositoryChunkVector vector : batch) {
            Objects.requireNonNull(vector, "vector");
            if (!coordinate.equals(vector.generation())) {
                throw new IllegalArgumentException(
                        "every vector must belong to the batch generation");
            }
            jdbc.update(
                    """
                    INSERT INTO crewscope.repository_chunk_embedding (
                        index_key, build_sequence, chunk_seq, organization_id, team_id,
                        repository_binding_id, path, language, start_line, end_line,
                        content_hash, content, model_key, model_revision, embedding, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS public.vector), now())
                    ON CONFLICT (index_key, build_sequence, chunk_seq) DO UPDATE SET
                        path = EXCLUDED.path,
                        language = EXCLUDED.language,
                        start_line = EXCLUDED.start_line,
                        end_line = EXCLUDED.end_line,
                        content_hash = EXCLUDED.content_hash,
                        content = EXCLUDED.content,
                        model_key = EXCLUDED.model_key,
                        model_revision = EXCLUDED.model_revision,
                        embedding = EXCLUDED.embedding,
                        created_at = EXCLUDED.created_at
                    """,
                    IndexKeyCodec.hash(coordinate.indexKey()),
                    coordinate.buildSequence(),
                    vector.chunkSeq(),
                    coordinate.indexKey().organizationId().value(),
                    coordinate.indexKey().teamId().value(),
                    coordinate.indexKey().repositoryBindingId().value(),
                    vector.path(),
                    vector.language(),
                    vector.startLine(),
                    vector.endLine(),
                    vector.contentHash(),
                    vector.content(),
                    vector.model().modelKey(),
                    vector.model().revision(),
                    VectorLiteral.of(vector.embedding()));
        }
    }

    @Override
    public int deleteByGeneration(RepositoryGenerationKey generation) {
        RepositoryGenerationKey coordinate = Objects.requireNonNull(generation, "generation");
        return jdbc.update(
                """
                DELETE FROM crewscope.repository_chunk_embedding
                WHERE index_key = ? AND build_sequence = ?
                """,
                IndexKeyCodec.hash(coordinate.indexKey()), coordinate.buildSequence());
    }

    @Override
    public List<ScoredRepositoryChunk> nearest(RepositoryChunkEmbeddingQuery query) {
        RepositoryChunkEmbeddingQuery value = Objects.requireNonNull(query, "query");
        RepositoryGenerationKey coordinate = value.generation();
        String literal = VectorLiteral.of(value.queryVector());
        return jdbc.query(
                """
                SELECT v.chunk_seq, v.path, v.language, v.start_line, v.end_line,
                       v.content_hash, v.content,
                       1 - (v.embedding <=> CAST(? AS public.vector)) AS similarity
                FROM crewscope.repository_chunk_embedding v
                WHERE v.organization_id = ?
                  AND v.team_id = ?
                  AND v.repository_binding_id = ?
                  AND v.index_key = ?
                  AND v.build_sequence = ?
                  AND v.model_key = ?
                  AND v.model_revision = ?
                ORDER BY v.embedding <=> CAST(? AS public.vector)
                LIMIT ?
                """,
                (rs, row) -> new ScoredRepositoryChunk(
                        rs.getInt("chunk_seq"),
                        rs.getString("path"),
                        rs.getString("language"),
                        rs.getInt("start_line"),
                        rs.getInt("end_line"),
                        rs.getString("content_hash"),
                        rs.getString("content"),
                        rs.getDouble("similarity")),
                literal,
                coordinate.indexKey().organizationId().value(),
                coordinate.indexKey().teamId().value(),
                coordinate.indexKey().repositoryBindingId().value(),
                IndexKeyCodec.hash(coordinate.indexKey()),
                coordinate.buildSequence(),
                coordinate.indexKey().embeddingModelRevision().modelKey(),
                coordinate.indexKey().embeddingModelRevision().revision(),
                literal,
                value.topK());
    }

    @Override
    public boolean isEnabled() {
        // The vector chain is a separate Flyway history: probe the table instead of assuming it.
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('crewscope.repository_chunk_embedding') IS NOT NULL",
                    Boolean.class));
        } catch (org.springframework.dao.DataAccessException unreachable) {
            return false;
        }
    }

    @Override
    public List<RepositoryChunkEmbeddingExportRow> pageByOrganization(
            OrganizationId organizationId, String afterCursor, int limit) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (limit <= 0) {
            return List.of();
        }
        // Keyset cursor "indexKey|buildSequence|chunkSeq" over the row order below.
        if (afterCursor == null || afterCursor.isBlank()) {
            return jdbc.query(
                    """
                    SELECT index_key, build_sequence, chunk_seq, team_id, repository_binding_id,
                           path, language, start_line, end_line, content_hash, model_key,
                           model_revision, embedding::text
                      FROM crewscope.repository_chunk_embedding
                     WHERE organization_id = ?
                     ORDER BY index_key, build_sequence, chunk_seq
                     LIMIT ?
                    """,
                    (rs, rowNum) -> rowOf(rs),
                    organizationId.value(),
                    limit);
        }
        String[] cursor = afterCursor.split("\\|", 3);
        if (cursor.length != 3) {
            throw new IllegalArgumentException("Malformed chunk page cursor");
        }
        return jdbc.query(
                """
                SELECT index_key, build_sequence, chunk_seq, team_id, repository_binding_id,
                       path, language, start_line, end_line, content_hash, model_key,
                       model_revision, embedding::text
                  FROM crewscope.repository_chunk_embedding
                 WHERE organization_id = ?
                   AND (index_key, build_sequence, chunk_seq) > (?, ?::bigint, ?::int)
                 ORDER BY index_key, build_sequence, chunk_seq
                 LIMIT ?
                """,
                (rs, rowNum) -> rowOf(rs),
                organizationId.value(),
                cursor[0],
                Long.parseLong(cursor[1]),
                Integer.parseInt(cursor[2]),
                limit);
    }

    private static RepositoryChunkEmbeddingExportRow rowOf(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        float[] embedding = VectorLiteral.parse(rs.getString("embedding"));
        return new RepositoryChunkEmbeddingExportRow(
                rs.getString("index_key"),
                rs.getLong("build_sequence"),
                rs.getInt("chunk_seq"),
                new TeamId(rs.getObject("team_id", java.util.UUID.class)),
                new io.crewscope.domain.coding.RepositoryBindingId(
                        rs.getObject("repository_binding_id", java.util.UUID.class)),
                rs.getString("path"),
                rs.getString("language"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                rs.getString("content_hash"),
                rs.getString("model_key"),
                rs.getLong("model_revision"),
                embedding.length,
                embedding);
    }
}
