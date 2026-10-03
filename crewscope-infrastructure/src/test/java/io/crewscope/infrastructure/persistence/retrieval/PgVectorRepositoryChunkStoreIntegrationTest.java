package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.RepositoryChunkVector;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * pgvector proof for repository chunk vectors (M10-I01b, vector-chain V3): the chain
 * only applies after the default chain committed V56, chunk rows obey the cross-chain
 * generation foreign key, text-literal vectors round-trip through idempotent upserts
 * positioned by (generation, chunkSeq), and retention deletes remove exactly one
 * generation.
 */
class PgVectorRepositoryChunkStoreIntegrationTest extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;
    private JdbcRepositoryGenerationStoreAdapter generations;
    private PgVectorRepositoryChunkStore store;

    @BeforeEach
    void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        generations = new JdbcRepositoryGenerationStoreAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        store = new PgVectorRepositoryChunkStore(jdbc);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedTenant();
    }

    @Test
    void appliesV3OnlyAfterTheDefaultChainCommittedV56() {
        // A vector chain without the default chain cannot resolve its cross-chain
        // foreign key, so the runner's ordering guarantee is structural, not advisory.
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");

        assertThrows(FlywayException.class, this::migrateVectorChain);
    }

    @Test
    void enforcesTheCrossChainGenerationForeignKey() {
        RepositoryIndexKey key = key();
        RepositoryGenerationKey unknown =
                new RepositoryGenerationKey(key, 7);

        DataIntegrityViolationException missing = assertThrows(
                DataIntegrityViolationException.class,
                () -> store.replaceBatch(unknown, List.of(chunk(unknown, 1, "alpha"))));
        assertTrue(missing.getMessage().contains("fk_repository_chunk_generation")
                        || missing.getMessage().contains("violates"),
                "the FK name or SQLSTATE must surface: " + missing.getMessage());
    }

    @Test
    void roundTripsTextLiteralVectorsIdempotently() {
        RepositoryIndexKey key = key();
        RepositoryGenerationKey generation = openGeneration(key);

        store.replaceBatch(generation, List.of(
                chunk(generation, 1, "alpha"),
                chunk(generation, 2, "beta")));
        assertEquals(2, chunkRows());

        // A crash-resumed build rewrites the same (generation, chunkSeq) positions.
        RepositoryChunkVector rewritten = chunk(generation, 2, "beta-v2");
        store.replaceBatch(generation, List.of(rewritten));
        assertEquals(2, chunkRows(), "the upsert overwrites in place, never duplicates");

        var row = jdbc.queryForMap(
                "SELECT path, language, start_line, end_line, content_hash, content,"
                        + " model_key, model_revision, embedding::text AS embedding"
                        + " FROM crewscope.repository_chunk_embedding"
                        + " WHERE index_key = ? AND build_sequence = ? AND chunk_seq = 2",
                IndexKeyCodec.hash(key), generation.buildSequence());
        // The upsert overwrites every projection column, path included.
        assertEquals("docs/beta-v2.md", row.get("path"));
        assertEquals("markdown", row.get("language"));
        assertEquals(1, ((Number) row.get("start_line")).intValue());
        assertEquals(4, ((Number) row.get("end_line")).intValue());
        assertEquals("b".repeat(64), row.get("content_hash"));
        assertEquals("beta-v2", row.get("content"));
        assertEquals("text-embedding-v4", row.get("model_key"));
        assertEquals(3L, ((Number) row.get("model_revision")).longValue());
        assertEquals("[" + String.join(",", java.util.Collections.nCopies(1024, "0.25")) + "]",
                row.get("embedding"));
    }

    @Test
    void deleteByGenerationRemovesExactlyOneGeneration() {
        RepositoryIndexKey key = key();
        RepositoryGenerationKey first = openGeneration(key);
        RepositoryGenerationKey second = openGeneration(key);
        store.replaceBatch(first, List.of(chunk(first, 1, "one"), chunk(first, 2, "two")));
        store.replaceBatch(second, List.of(chunk(second, 1, "three")));

        assertEquals(2, store.deleteByGeneration(first));
        assertEquals(1, chunkRows());
        assertEquals(0, store.deleteByGeneration(first), "a replayed delete is a no-op");
        assertEquals(1, chunkRows(), "the other generation keeps its vectors");
    }

    // ------------------------------------------------------------------ fixtures

    private RepositoryIndexKey key() {
        return new RepositoryIndexKey(
                organizationId, teamId, bindingId,
                new SourceCommit("0123456789012345678901234567890123456789"),
                ChunkingPolicy.defaults().policyHash(), MODEL);
    }

    private RepositoryGenerationKey openGeneration(RepositoryIndexKey key) {
        jobs.findLiveByIndexKey(key).ifPresent(this::retire);
        UUID job = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, WorkProjectId.generate(),
                key, actor, NOW)).id();
        return generations.open(key, job).generationKey();
    }

    private void retire(KnowledgeIndexJob live) {
        KnowledgeIndexJob claim = jobs.claimNext("worker-test", NOW, LEASE).orElseThrow();
        assertEquals(live.id(), claim.id(), "the only live job of the coordinate is claimed");
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        org.junit.jupiter.api.Assertions.assertTrue(jobs.updateClaimed(
                claim.failed("SUPERSEDED", done), "worker-test", done, LEASE).isPresent());
    }

    private RepositoryChunkVector chunk(
            RepositoryGenerationKey generation, int chunkSeq, String content) {
        return new RepositoryChunkVector(
                generation, chunkSeq, "docs/" + content + ".md", "markdown",
                1, 4, "b".repeat(64), content, MODEL, filled(0.25f));
    }

    private static float[] filled(float value) {
        float[] embedding = new float[MODEL.dimension()];
        java.util.Arrays.fill(embedding, value);
        return embedding;
    }

    private int chunkRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.repository_chunk_embedding", Integer.class);
    }

    private void migrateDefaultChain() {
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .load()
                .migrate();
    }

    private void migrateVectorChain() {
        Flyway.configure()
                .dataSource(PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword())
                .locations("classpath:db/migration-vector")
                .schemas("crewscope")
                .defaultSchema("crewscope")
                .table("flyway_vector_history")
                .validateMigrationNaming(true)
                .createSchemas(false)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private void seedTenant() {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Chunk Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Chunk Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Chunk owner', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
    }
}
