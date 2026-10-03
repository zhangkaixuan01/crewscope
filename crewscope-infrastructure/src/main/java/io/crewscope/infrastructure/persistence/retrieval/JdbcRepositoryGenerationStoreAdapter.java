package io.crewscope.infrastructure.persistence.retrieval;

import io.crewscope.application.retrieval.RepositoryGeneration;
import io.crewscope.application.retrieval.RepositoryGenerationStore;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.GenerationStatus;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.id.PrincipalId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC write side for repository index generations (M10-I01b). Activation is one
 * transaction: the new generation flips to ACTIVE (CAS), the previous ACTIVE row
 * retires, and generations beyond the retention window are purged together with their
 * chunk-vector rows — the vector table shares this schema, so the purge is atomic. The
 * partial unique index {@code ux_repository_generation_one_active} is the activation
 * pointer: no side table, no drift.
 *
 * <p>Deliberately not a {@code @Repository}: the activation purge touches the
 * vector-chain chunk table, so the server assembly only creates this bean when the
 * vector store is enabled — the same precondition as the other retrieval beans.
 */
public class JdbcRepositoryGenerationStoreAdapter implements RepositoryGenerationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcRepositoryGenerationStoreAdapter(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public RepositoryGeneration open(RepositoryIndexKey indexKey, UUID jobId) {
        RepositoryIndexKey required = Objects.requireNonNull(indexKey, "indexKey");
        Objects.requireNonNull(jobId, "jobId");
        String hash = IndexKeyCodec.hash(required);
        Optional<RepositoryGeneration> resumable = first("""
                SELECT * FROM crewscope.repository_index_generation
                WHERE index_key = :indexKey AND job_id = :jobId
                  AND status IN ('BUILDING', 'VALIDATING')
                ORDER BY build_sequence DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("indexKey", hash)
                .addValue("jobId", jobId));
        if (resumable.isPresent()) {
            return resumable.get();
        }
        return jdbc.query("""
                INSERT INTO crewscope.repository_index_generation
                    (index_key, build_sequence, organization_id, team_id,
                     repository_binding_id, source_commit, chunk_policy_hash, model_key,
                     model_dimension, model_revision, job_id, status, created_at, updated_at)
                SELECT :indexKey, COALESCE(max(build_sequence), 0) + 1,
                       :organizationId, :teamId, :repositoryBindingId, :sourceCommit,
                       :chunkPolicyHash, :modelKey, :modelDimension, :modelRevision,
                       :jobId, 'BUILDING', now(), now()
                FROM crewscope.repository_index_generation
                WHERE index_key = :indexKey
                RETURNING *
                """, keyParams(required).addValue("jobId", jobId),
                (row, number) -> map(row)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "generation open must return the inserted row"));
    }

    @Override
    public boolean activate(
            RepositoryGenerationKey generationKey,
            GenerationRetentionPolicy retention,
            PrincipalId activatedBy) {
        RepositoryGenerationKey required = Objects.requireNonNull(generationKey, "generationKey");
        GenerationRetentionPolicy policy = Objects.requireNonNull(retention, "retention");
        PrincipalId actor = Objects.requireNonNull(activatedBy, "activatedBy");
        String hash = IndexKeyCodec.hash(required.indexKey());
        long purgeBelow = required.buildSequence() - policy.maxRetainedGenerations();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("indexKey", hash)
                .addValue("buildSequence", required.buildSequence())
                .addValue("activator", actor.value())
                .addValue("purgeBelow", purgeBelow);
        Boolean activated = transactions.execute(status -> {
            // Retire first: the one-ACTIVE partial unique index checks on row update, so
            // flipping the new generation while the old pointer still reads ACTIVE would
            // collide immediately. If the CAS flip below misses, this whole transaction
            // rolls back and the previous pointer is untouched.
            jdbc.update("""
                    UPDATE crewscope.repository_index_generation
                    SET status = 'RETIRED', activated_at = NULL,
                        activated_by_principal_id = NULL, updated_at = now()
                    WHERE index_key = :indexKey AND status = 'ACTIVE'
                      AND build_sequence <> :buildSequence
                    """, parameters);
            int flipped = jdbc.update("""
                    UPDATE crewscope.repository_index_generation
                    SET status = 'ACTIVE', activated_at = now(),
                        activated_by_principal_id = :activator, updated_at = now()
                    WHERE index_key = :indexKey AND build_sequence = :buildSequence
                      AND status IN ('BUILDING', 'VALIDATING')
                    """, parameters);
            if (flipped == 0) {
                status.setRollbackOnly();
                return false;
            }
            if (purgeBelow >= 1) {
                jdbc.update("""
                        DELETE FROM crewscope.repository_chunk_embedding
                        WHERE index_key = :indexKey AND build_sequence <= :purgeBelow
                        """, parameters);
                jdbc.update("""
                        DELETE FROM crewscope.repository_index_generation
                        WHERE index_key = :indexKey AND build_sequence <= :purgeBelow
                        """, parameters);
            }
            return true;
        });
        return Boolean.TRUE.equals(activated);
    }

    @Override
    public void fail(RepositoryGenerationKey generationKey) {
        RepositoryGenerationKey required = Objects.requireNonNull(generationKey, "generationKey");
        jdbc.update("""
                UPDATE crewscope.repository_index_generation
                SET status = 'FAILED', updated_at = now()
                WHERE index_key = :indexKey AND build_sequence = :buildSequence
                  AND status IN ('BUILDING', 'VALIDATING')
                """, new MapSqlParameterSource()
                .addValue("indexKey", IndexKeyCodec.hash(required.indexKey()))
                .addValue("buildSequence", required.buildSequence()));
    }

    // ------------------------------------------------------------------ mapping

    private Optional<RepositoryGeneration> first(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, number) -> map(row)).stream().findFirst();
    }

    private static RepositoryGeneration map(ResultSet row) throws SQLException {
        RepositoryIndexKey indexKey = IndexKeyCodec.reconstitute(
                new io.crewscope.domain.shared.id.OrganizationId(
                        row.getObject("organization_id", UUID.class)),
                new io.crewscope.domain.shared.id.TeamId(
                        row.getObject("team_id", UUID.class)),
                new io.crewscope.domain.coding.RepositoryBindingId(
                        row.getObject("repository_binding_id", UUID.class)),
                row.getString("source_commit"),
                row.getString("chunk_policy_hash"),
                row.getString("model_key"),
                row.getInt("model_dimension"),
                row.getLong("model_revision"));
        return new RepositoryGeneration(
                new RepositoryGenerationKey(indexKey, row.getLong("build_sequence")),
                row.getObject("job_id", UUID.class),
                GenerationStatus.valueOf(row.getString("status")));
    }

    private static MapSqlParameterSource keyParams(RepositoryIndexKey key) {
        return new MapSqlParameterSource()
                .addValue("indexKey", IndexKeyCodec.hash(key))
                .addValue("organizationId", key.organizationId().value())
                .addValue("teamId", key.teamId().value())
                .addValue("repositoryBindingId", key.repositoryBindingId().value())
                .addValue("sourceCommit", key.sourceCommit().value())
                .addValue("chunkPolicyHash", key.chunkingPolicyHash().value())
                .addValue("modelKey", key.embeddingModelRevision().modelKey())
                .addValue("modelDimension", key.embeddingModelRevision().dimension())
                .addValue("modelRevision", key.embeddingModelRevision().revision());
    }
}
