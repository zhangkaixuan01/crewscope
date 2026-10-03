package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.retrieval.GenerationSnapshot;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * PostgreSQL proof for repository index generations (M10-I01b, V56 + vector-chain V3):
 * activation is one transaction that flips the CAS, retires the previous ACTIVE row and
 * purges generations beyond the retention window together with their chunk-vector rows;
 * a failed build never touches the ACTIVE pointer; open() is monotonic per coordinate
 * and resumable per job; and the catalog exposes only activated generations.
 */
class RepositoryIndexGenerationIntegrationTest extends AbstractPgVectorContainerIntegrationTest {

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
    private JdbcRepositoryGenerationStoreAdapter store;
    private JdbcGenerationCatalogAdapter catalog;

    @BeforeEach
    void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        store = new JdbcRepositoryGenerationStoreAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        catalog = new JdbcGenerationCatalogAdapter(jdbc);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedTenant();
    }

    @Test
    void activatesTheNewGenerationAndRetiresTheOldOneInOneTransaction() {
        RepositoryIndexKey key = key();
        UUID firstJob = repositoryJob(key);
        long first = store.open(key, firstJob).generationKey().buildSequence();
        seedChunkRows(key, first, 2);

        assertTrue(store.activate(sequence(key, first), GenerationRetentionPolicy.DEFAULT, actor));
        assertEquals("ACTIVE", statusOf(key, first));
        assertEquals(
                actor.value(),
                jdbc.queryForObject(
                        "SELECT activated_by_principal_id FROM crewscope.repository_index_generation"
                                + " WHERE index_key = ? AND build_sequence = ?",
                        UUID.class, IndexKeyCodec.hash(key), first));
        assertFalse(store.activate(sequence(key, first), GenerationRetentionPolicy.DEFAULT, actor),
                "the CAS flip refuses an already-ACTIVE row");

        UUID secondJob = repositoryJob(key);
        long second = store.open(key, secondJob).generationKey().buildSequence();
        assertEquals(first + 1, second);
        seedChunkRows(key, second, 1);
        assertTrue(store.activate(sequence(key, second), GenerationRetentionPolicy.DEFAULT, actor));

        assertEquals("RETIRED", statusOf(key, first), "the previous pointer retires");
        assertEquals("ACTIVE", statusOf(key, second));
        Optional<GenerationSnapshot> active = catalog.findActiveGeneration(key);
        assertTrue(active.isPresent());
        assertEquals(second, active.get().generationKey().buildSequence());
        assertEquals(actor, active.get().activation().createdBy().orElseThrow());
    }

    @Test
    void retainsTwoGenerationsAndPurgesOlderOnesWithTheirVectors() {
        RepositoryIndexKey key = key();
        for (int build = 1; build <= 3; build++) {
            UUID job = repositoryJob(key);
            long sequence = store.open(key, job).generationKey().buildSequence();
            assertEquals(build, sequence);
            seedChunkRows(key, sequence, 1);
            assertTrue(store.activate(
                    sequence(key, sequence), GenerationRetentionPolicy.DEFAULT, actor));
        }

        // Activating build 3 purges build 1 (3 - 2 retained): generation row and its
        // chunk vectors go together, in the same transaction.
        assertEquals(List.of("RETIRED", "ACTIVE"), statusesInOrder(key));
        assertEquals(2, chunkRows(key), "only the retained generations keep vectors");

        UUID fourthJob = repositoryJob(key);
        long fourth = store.open(key, fourthJob).generationKey().buildSequence();
        seedChunkRows(key, fourth, 1);
        assertTrue(store.activate(sequence(key, fourth), GenerationRetentionPolicy.DEFAULT, actor));

        assertEquals(List.of("RETIRED", "ACTIVE"), statusesInOrder(key));
        assertEquals(2, chunkRows(key), "the retention window slides with every activation");
    }

    @Test
    void aFailedBuildNeverTouchesTheActiveGeneration() {
        RepositoryIndexKey key = key();
        UUID firstJob = repositoryJob(key);
        long first = store.open(key, firstJob).generationKey().buildSequence();
        seedChunkRows(key, first, 1);
        assertTrue(store.activate(sequence(key, first), GenerationRetentionPolicy.DEFAULT, actor));

        UUID secondJob = repositoryJob(key);
        long second = store.open(key, secondJob).generationKey().buildSequence();
        store.fail(sequence(key, second));
        // Failing a terminal generation again is a zero-row no-op.
        store.fail(sequence(key, second));

        assertEquals("FAILED", statusOf(key, second));
        assertEquals("ACTIVE", statusOf(key, first), "a failed build never demotes the pointer");
        assertEquals(
                first,
                catalog.findActiveGeneration(key)
                        .orElseThrow().generationKey().buildSequence());
    }

    @Test
    void openIsMonotonicPerCoordinateAndResumablePerJob() {
        RepositoryIndexKey key = key();
        UUID firstJob = repositoryJob(key);
        long first = store.open(key, firstJob).generationKey().buildSequence();

        long resumed = store.open(key, firstJob).generationKey().buildSequence();
        assertEquals(first, resumed, "the same job resumes its own BUILDING generation");

        UUID secondJob = repositoryJob(key);
        assertEquals(first + 1, store.open(key, secondJob).generationKey().buildSequence());
        UUID thirdJob = repositoryJob(key);
        assertEquals(first + 2, store.open(key, thirdJob).generationKey().buildSequence());
    }

    @Test
    void theCatalogExposesOnlyActivatedGenerations() {
        RepositoryIndexKey key = key();
        assertTrue(catalog.findActiveGeneration(key).isEmpty(),
                "an unknown coordinate has no generation");

        UUID job = repositoryJob(key);
        long sequence = store.open(key, job).generationKey().buildSequence();
        assertTrue(catalog.findActiveGeneration(key).isEmpty(),
                "a BUILDING generation is invisible to readers");
        seedChunkRows(key, sequence, 1);
        assertTrue(store.activate(sequence(key, sequence), GenerationRetentionPolicy.DEFAULT, actor));

        GenerationSnapshot snapshot = catalog.findActiveGeneration(key).orElseThrow();
        assertEquals(key, snapshot.generationKey().indexKey(),
                "the typed key is reconstituted from the decomposed columns");
        assertEquals(sequence, snapshot.generationKey().buildSequence());
    }

    @Test
    void independentCoordinatesKeepIndependentSequencesAndPointers() {
        RepositoryIndexKey first = key();
        RepositoryIndexKey second = otherCommitKey();
        UUID firstJob = repositoryJob(first);
        UUID secondJob = repositoryJob(second);
        long firstSequence = store.open(first, firstJob).generationKey().buildSequence();
        long secondSequence = store.open(second, secondJob).generationKey().buildSequence();

        assertEquals(1, firstSequence);
        assertEquals(1, secondSequence, "each coordinate numbers its builds from one");
        seedChunkRows(first, firstSequence, 1);
        seedChunkRows(second, secondSequence, 1);
        assertTrue(store.activate(sequence(first, firstSequence), GenerationRetentionPolicy.DEFAULT, actor));
        assertTrue(store.activate(sequence(second, secondSequence), GenerationRetentionPolicy.DEFAULT, actor));

        assertEquals(1, catalog.findActiveGeneration(first)
                .orElseThrow().generationKey().buildSequence());
        assertEquals(1, catalog.findActiveGeneration(second)
                .orElseThrow().generationKey().buildSequence());
    }

    // ------------------------------------------------------------------ fixtures

    private RepositoryIndexKey key() {
        return new RepositoryIndexKey(
                organizationId, teamId, bindingId,
                new SourceCommit("0123456789012345678901234567890123456789"),
                ChunkingPolicy.defaults().policyHash(), MODEL);
    }

    private RepositoryIndexKey otherCommitKey() {
        return new RepositoryIndexKey(
                organizationId, teamId, bindingId,
                new SourceCommit("fedcba9876543210fedcba9876543210fedcba98"),
                ChunkingPolicy.defaults().policyHash(), MODEL);
    }

    private static io.crewscope.domain.retrieval.RepositoryGenerationKey sequence(
            RepositoryIndexKey key, long buildSequence) {
        return new io.crewscope.domain.retrieval.RepositoryGenerationKey(key, buildSequence);
    }

    /**
     * Enqueues one repository job for the coordinate, first retiring any live job of the
     * same coordinate — the live-job unique index admits only one QUEUED/running job per
     * index key, so sequential builds reuse the slot exactly like the service does.
     */
    private UUID repositoryJob(RepositoryIndexKey key) {
        jobs.findLiveByIndexKey(key).ifPresent(this::retire);
        return jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, WorkProjectId.generate(),
                key, actor, NOW)).id();
    }

    private void retire(KnowledgeIndexJob live) {
        KnowledgeIndexJob claim = jobs.claimNext("worker-test", NOW, LEASE).orElseThrow();
        assertEquals(live.id(), claim.id(), "the only live job of the coordinate is claimed");
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        assertTrue(jobs.updateClaimed(
                claim.failed("SUPERSEDED", done), "worker-test", done, LEASE).isPresent());
    }

    private String statusOf(RepositoryIndexKey key, long buildSequence) {
        return jdbc.queryForObject(
                "SELECT status FROM crewscope.repository_index_generation"
                        + " WHERE index_key = ? AND build_sequence = ?",
                String.class, IndexKeyCodec.hash(key), buildSequence);
    }

    private List<String> statusesInOrder(RepositoryIndexKey key) {
        return jdbc.queryForList(
                "SELECT status FROM crewscope.repository_index_generation"
                        + " WHERE index_key = ? ORDER BY build_sequence",
                String.class, IndexKeyCodec.hash(key));
    }

    private int chunkRows(RepositoryIndexKey key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.repository_chunk_embedding WHERE index_key = ?",
                Integer.class, IndexKeyCodec.hash(key));
    }

    private void seedChunkRows(RepositoryIndexKey key, long buildSequence, int chunks) {
        for (int chunk = 1; chunk <= chunks; chunk++) {
            jdbc.update(
                    """
                    INSERT INTO crewscope.repository_chunk_embedding
                        (index_key, build_sequence, chunk_seq, organization_id, team_id,
                         repository_binding_id, path, language, start_line, end_line,
                         content_hash, content, model_key, model_revision, embedding, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'README.md', 'markdown', 1, 10, ?, 'content',
                            'text-embedding-v4', 3, CAST(? AS public.vector), now())
                    """,
                    IndexKeyCodec.hash(key), buildSequence, chunk, organizationId.value(),
                    teamId.value(), bindingId.value(), "c".repeat(64), vectorLiteral());
        }
    }

    private static String vectorLiteral() {
        return "[" + String.join(",", Collections.nCopies(MODEL.dimension(), "1")) + "]";
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
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Generation Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Generation Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Generation owner', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
    }
}
