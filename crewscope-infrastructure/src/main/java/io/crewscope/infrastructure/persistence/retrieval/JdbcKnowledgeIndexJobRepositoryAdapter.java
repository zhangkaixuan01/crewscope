package io.crewscope.infrastructure.persistence.retrieval;

import io.crewscope.application.embedding.TeamEmbeddingService;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexJobRepository;
import io.crewscope.application.retrieval.KnowledgeIndexJobSource;
import io.crewscope.application.retrieval.KnowledgeIndexJobStatus;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC persistence for knowledge-index jobs and checkpoints (M10-I01b). Claiming is a
 * CTE {@code FOR UPDATE SKIP LOCKED} over live jobs that bumps {@code attempt} and the
 * fencing {@code claim_token} and enters CHUNKING; every worker write is a CAS on
 * (id, claimToken, claimedBy, unexpired lease, non-terminal status) whose empty result
 * is the observable FENCE. Checkpoints insert only while the token still owns the job.
 */
@Repository
public class JdbcKnowledgeIndexJobRepositoryAdapter
        implements KnowledgeIndexJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcKnowledgeIndexJobRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public KnowledgeIndexJob create(KnowledgeIndexJob job) {
        KnowledgeIndexJob required = Objects.requireNonNull(job, "job");
        jdbc.update("""
                INSERT INTO crewscope.knowledge_index_job
                (id, organization_id, team_id, source, entry_id, project_id,
                 repository_binding_id, source_commit, chunk_policy_hash, model_key,
                 model_revision, index_key, status, attempt, chunks_done, chunks_total,
                 failure_code, generation_build_sequence, claimed_by, claim_token,
                 lease_expires_at, created_by_principal_id, created_at, updated_at)
                VALUES (:id, :organizationId, :teamId, :source, :entryId, :projectId,
                 :repositoryBindingId, :sourceCommit, :chunkPolicyHash, :modelKey,
                 :modelRevision, :indexKey, :status, :attempt, :chunksDone, :chunksTotal,
                 :failureCode, :generationBuildSequence, :claimedBy, :claimToken,
                 :leaseExpiresAt, :createdBy, :createdAt, :updatedAt)
                """, params(required));
        return required;
    }

    @Override
    public Optional<KnowledgeIndexJob> findById(
            OrganizationId organizationId, TeamId teamId, UUID jobId) {
        return first("""
                SELECT * FROM crewscope.knowledge_index_job
                WHERE organization_id = :organizationId AND team_id = :teamId AND id = :id
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("teamId", teamId.value())
                .addValue("id", jobId));
    }

    @Override
    public Optional<KnowledgeIndexJob> findLiveByEntry(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return first("""
                SELECT * FROM crewscope.knowledge_index_job
                WHERE organization_id = :organizationId AND team_id = :teamId
                  AND entry_id = :entryId
                  AND status IN ('QUEUED', 'CHUNKING', 'EMBEDDING', 'ACTIVATING')
                ORDER BY created_at, id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("teamId", teamId.value())
                .addValue("entryId", entryId.value()));
    }

    @Override
    public Optional<KnowledgeIndexJob> findLiveByIndexKey(RepositoryIndexKey indexKey) {
        return first("""
                SELECT * FROM crewscope.knowledge_index_job
                WHERE index_key = :indexKey
                  AND status IN ('QUEUED', 'CHUNKING', 'EMBEDDING', 'ACTIVATING')
                ORDER BY created_at, id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("indexKey", IndexKeyCodec.hash(indexKey)));
    }

    @Override
    public Optional<KnowledgeIndexJob> findLatestByEntry(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return first("""
                SELECT * FROM crewscope.knowledge_index_job
                WHERE organization_id = :organizationId AND team_id = :teamId
                  AND entry_id = :entryId
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId.value())
                .addValue("teamId", teamId.value())
                .addValue("entryId", entryId.value()));
    }

    @Override
    public Optional<KnowledgeIndexJob> claimNext(
            String owner, UtcTimestamp now, Duration leaseDuration) {
        String leaseOwner = requireOwner(owner);
        OffsetDateTime claimedAt = offset(now);
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                now.value().plus(requireDuration(leaseDuration)), ZoneOffset.UTC);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM crewscope.knowledge_index_job
                    WHERE status = 'QUEUED'
                       OR (status IN ('CHUNKING', 'EMBEDDING', 'ACTIVATING')
                           AND lease_expires_at <= :claimedAt)
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE crewscope.knowledge_index_job AS target
                SET status = 'CHUNKING',
                    attempt = target.attempt + 1,
                    claim_token = target.claim_token + 1,
                    claimed_by = :leaseOwner,
                    lease_expires_at = :leaseExpiresAt,
                    updated_at = :claimedAt
                FROM candidate
                WHERE target.id = candidate.id
                RETURNING target.*
                """, new MapSqlParameterSource()
                        .addValue("claimedAt", claimedAt)
                        .addValue("leaseOwner", leaseOwner)
                        .addValue("leaseExpiresAt", expiresAt),
                (row, number) -> map(row)).stream().findFirst();
    }

    @Override
    public Optional<KnowledgeIndexJob> updateClaimed(
            KnowledgeIndexJob job, String owner, UtcTimestamp now, Duration leaseDuration) {
        KnowledgeIndexJob required = Objects.requireNonNull(job, "job");
        String leaseOwner = requireOwner(owner);
        boolean terminal = required.status().terminal();
        MapSqlParameterSource parameters = params(required)
                .addValue("leaseOwner", leaseOwner)
                .addValue("now", offset(now))
                .addValue("nextLeaseOwner", terminal ? null : leaseOwner)
                .addValue("nextLeaseExpiresAt", terminal ? null
                        : OffsetDateTime.ofInstant(
                                now.value().plus(requireDuration(leaseDuration)),
                                ZoneOffset.UTC));
        return jdbc.query("""
                UPDATE crewscope.knowledge_index_job
                SET status = :status, attempt = :attempt,
                    chunks_done = :chunksDone, chunks_total = :chunksTotal,
                    failure_code = :failureCode,
                    generation_build_sequence = :generationBuildSequence,
                    claimed_by = :nextLeaseOwner,
                    lease_expires_at = :nextLeaseExpiresAt,
                    updated_at = :updatedAt
                WHERE id = :id
                  AND claim_token = :claimToken
                  AND claimed_by = :leaseOwner
                  AND lease_expires_at > :now
                  AND status IN ('CHUNKING', 'EMBEDDING', 'ACTIVATING')
                RETURNING *
                """, parameters, (row, number) -> map(row)).stream().findFirst();
    }

    @Override
    public boolean insertCheckpoint(
            UUID jobId, long claimToken, int chunkSeq, int chunkCount) {
        Objects.requireNonNull(jobId, "jobId");
        if (chunkSeq < 1 || chunkCount < 1 || claimToken < 0) {
            throw new IllegalArgumentException(
                    "checkpoint seq and count must be positive and the token non-negative");
        }
        return jdbc.update("""
                INSERT INTO crewscope.knowledge_index_checkpoint
                    (job_id, chunk_seq, claim_token, chunk_count, created_at)
                SELECT :jobId, :chunkSeq, :claimToken, :chunkCount, now()
                WHERE EXISTS (
                    SELECT 1 FROM crewscope.knowledge_index_job
                    WHERE id = :jobId
                      AND claim_token = :claimToken
                      AND status IN ('CHUNKING', 'EMBEDDING', 'ACTIVATING'))
                """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("claimToken", claimToken)
                .addValue("chunkSeq", chunkSeq)
                .addValue("chunkCount", chunkCount)) > 0;
    }

    @Override
    public int maxCheckpointSeq(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        Integer max = jdbc.queryForObject("""
                SELECT max(chunk_seq + chunk_count - 1)
                FROM crewscope.knowledge_index_checkpoint
                WHERE job_id = :jobId
                """, new MapSqlParameterSource().addValue("jobId", jobId), Integer.class);
        return max == null ? 0 : max;
    }

    @Override
    public Optional<KnowledgeIndexJob> cancelQueued(
            KnowledgeIndexJob job, UtcTimestamp cancelledAt) {
        KnowledgeIndexJob required = Objects.requireNonNull(job, "job");
        return jdbc.query("""
                UPDATE crewscope.knowledge_index_job
                SET status = 'CANCELLED', failure_code = 'CANCELLED',
                    claimed_by = NULL, lease_expires_at = NULL,
                    updated_at = :cancelledAt
                WHERE id = :id AND status = 'QUEUED'
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("id", required.id())
                .addValue("cancelledAt", offset(cancelledAt)),
                (row, number) -> map(row)).stream().findFirst();
    }

    // ------------------------------------------------------------------ mapping

    private Optional<KnowledgeIndexJob> first(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, number) -> map(row)).stream().findFirst();
    }

    private static KnowledgeIndexJob map(ResultSet row) throws SQLException {
        KnowledgeIndexJobSource source = KnowledgeIndexJobSource.valueOf(row.getString("source"));
        Optional<KnowledgeEntryId> entryId = Optional.ofNullable(
                row.getObject("entry_id", UUID.class)).map(KnowledgeEntryId::new);
        Optional<WorkProjectId> projectId = Optional.ofNullable(
                row.getObject("project_id", UUID.class)).map(WorkProjectId::new);
        Optional<RepositoryIndexKey> indexKey = row.getString("index_key") == null
                ? Optional.empty()
                : Optional.of(IndexKeyCodec.reconstitute(
                        new OrganizationId(row.getObject("organization_id", UUID.class)),
                        new TeamId(row.getObject("team_id", UUID.class)),
                        new RepositoryBindingId(
                                row.getObject("repository_binding_id", UUID.class)),
                        row.getString("source_commit"),
                        row.getString("chunk_policy_hash"),
                        row.getString("model_key"),
                        // The job table deliberately stores no model_dimension: S01
                        // freezes 1024, and V56's CHECK pins it — reconstituting from
                        // the same constant keeps the key and the schema in lockstep.
                        TeamEmbeddingService.EMBEDDING_DIMENSION,
                        row.getLong("model_revision")));
        return new KnowledgeIndexJob(
                row.getObject("id", UUID.class),
                new OrganizationId(row.getObject("organization_id", UUID.class)),
                new TeamId(row.getObject("team_id", UUID.class)),
                source,
                entryId,
                projectId,
                indexKey,
                KnowledgeIndexJobStatus.valueOf(row.getString("status")),
                row.getInt("attempt"),
                row.getInt("chunks_done"),
                row.getInt("chunks_total"),
                Optional.ofNullable(row.getString("failure_code")),
                row.getLong("generation_build_sequence"),
                Optional.ofNullable(row.getString("claimed_by")),
                row.getLong("claim_token"),
                Optional.ofNullable(row.getObject("lease_expires_at", OffsetDateTime.class))
                        .map(value -> UtcTimestamp.from(value.toInstant())),
                new PrincipalId(row.getObject("created_by_principal_id", UUID.class)),
                UtcTimestamp.from(row.getObject("created_at", OffsetDateTime.class).toInstant()),
                UtcTimestamp.from(row.getObject("updated_at", OffsetDateTime.class).toInstant()));
    }

    private static MapSqlParameterSource params(KnowledgeIndexJob job) {
        return new MapSqlParameterSource()
                .addValue("id", job.id())
                .addValue("organizationId", job.organizationId().value())
                .addValue("teamId", job.teamId().value())
                .addValue("source", job.source().name())
                .addValue("entryId", job.entryId().map(KnowledgeEntryId::value).orElse(null))
                .addValue("projectId", job.projectId().map(WorkProjectId::value).orElse(null))
                .addValue("repositoryBindingId", job.indexKey()
                        .map(key -> key.repositoryBindingId().value()).orElse(null))
                .addValue("sourceCommit", job.indexKey()
                        .map(key -> key.sourceCommit().value()).orElse(null))
                .addValue("chunkPolicyHash", job.indexKey()
                        .map(key -> key.chunkingPolicyHash().value()).orElse(null))
                .addValue("modelKey", job.indexKey()
                        .map(key -> key.embeddingModelRevision().modelKey()).orElse(null))
                .addValue("modelRevision", job.indexKey()
                        .map(key -> key.embeddingModelRevision().revision()).orElse(null))
                .addValue("indexKey", job.indexKey().map(IndexKeyCodec::hash).orElse(null))
                .addValue("status", job.status().name())
                .addValue("attempt", job.attempt())
                .addValue("chunksDone", job.chunksDone())
                .addValue("chunksTotal", job.chunksTotal())
                .addValue("failureCode", job.failureCode().orElse(null))
                .addValue("generationBuildSequence", job.generationBuildSequence())
                .addValue("claimedBy", job.claimedBy().orElse(null))
                .addValue("claimToken", job.claimToken())
                .addValue("leaseExpiresAt", job.leaseExpiresAt()
                        .map(value -> OffsetDateTime.ofInstant(value.value(), ZoneOffset.UTC))
                        .orElse(null))
                .addValue("createdBy", job.createdBy().value())
                .addValue("createdAt", OffsetDateTime.ofInstant(
                        job.createdAt().value(), ZoneOffset.UTC))
                .addValue("updatedAt", OffsetDateTime.ofInstant(
                        job.updatedAt().value(), ZoneOffset.UTC));
    }

    private static OffsetDateTime offset(UtcTimestamp value) {
        return OffsetDateTime.ofInstant(
                Objects.requireNonNull(value, "now").value(), ZoneOffset.UTC);
    }

    private static String requireOwner(String value) {
        String normalized = Objects.requireNonNull(value, "owner").strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new IllegalArgumentException("owner must contain 1 to 160 characters");
        }
        return normalized;
    }

    private static Duration requireDuration(Duration value) {
        Duration required = Objects.requireNonNull(value, "leaseDuration");
        if (required.isZero() || required.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return required;
    }
}
