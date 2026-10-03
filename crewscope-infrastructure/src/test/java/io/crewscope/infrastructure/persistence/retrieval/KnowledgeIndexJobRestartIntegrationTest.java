package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.retrieval.KnowledgeEmbeddingExecutor;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexJobService;
import io.crewscope.application.retrieval.KnowledgeIndexJobStatus;
import io.crewscope.application.retrieval.KnowledgeIndexWorker;
import io.crewscope.application.retrieval.RepositoryChunkVector;
import io.crewscope.application.retrieval.RepositoryContentPort;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileContent;
import io.crewscope.application.retrieval.RepositoryContentPort.RepositoryFileRef;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.knowledge.PgVectorKnowledgeEmbeddingStore;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * PostgreSQL recovery proof for durable index jobs (M10-I01b): a lease that expires
 * mid-build resumes at the last committed checkpoint without duplicating rows, a writer
 * whose claim was taken away touches zero state (its vector writes are idempotent
 * overwrites, its checkpoints and status transitions are rejected), and a closed
 * refresh gate never strands already-queued jobs — the queue keeps draining.
 */
class KnowledgeIndexJobRestartIntegrationTest extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final int FILES = 12;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();

    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;
    private JdbcRepositoryGenerationStoreAdapter generations;
    private PgVectorRepositoryChunkStore chunkVectors;
    private PgVectorKnowledgeEmbeddingStore knowledgeVectors;
    private RecordingEmbeddings embeddings;
    private KnowledgeRepository knowledge;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        generations = new JdbcRepositoryGenerationStoreAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        chunkVectors = new PgVectorRepositoryChunkStore(jdbc);
        knowledgeVectors = new PgVectorKnowledgeEmbeddingStore(jdbc);
        embeddings = new RecordingEmbeddings();
        knowledge = mock(KnowledgeRepository.class);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedTenant();
    }

    @Test
    void resumesAtTheLastCheckpointWithoutDuplicatingRows() {
        RepositoryIndexKey key = coordinate();
        jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId,
                WorkProjectId.generate(), key, actor, NOW));
        KnowledgeIndexJob first = jobs.claimNext("worker-a", NOW, LEASE).orElseThrow();
        RepositoryGenerationKey generation = generations.open(key, first.id()).generationKey();
        // Handover replay of a first pass that committed rows then lost its lease: one
        // embedding batch of ten landed, its checkpoint too.
        first = jobs.updateClaimed(
                first.withStatus(KnowledgeIndexJobStatus.EMBEDDING, NOW)
                        .withProgress(10, FILES, NOW),
                "worker-a", NOW, LEASE).orElseThrow();
        chunkVectors.replaceBatch(generation, handcraftedVectors(generation, 1, 10));
        assertTrue(jobs.insertCheckpoint(first.id(), first.claimToken(), 1, 10));
        expireLease();

        var result = worker("worker-b").runOnce();

        assertEquals(1, result.claimedJobs());
        assertEquals(0, result.failedJobs());
        assertEquals(
                KnowledgeIndexJobStatus.READY,
                jobs.findById(organizationId, teamId, first.id()).orElseThrow().status(),
                "the resumed pass finishes the job");
        assertEquals(FILES, chunkRows(), "twelve chunk positions, never a duplicate");
        assertEquals(2, embeddings.embeddedInputs().size(),
                "the resume embeds only the batches after the checkpoint");
        assertTrue(embeddings.embeddedInputs().stream()
                        .anyMatch(input -> input.contains("class File11")),
                "the resumed batch carries the eleventh file: " + embeddings.embeddedInputs());
        assertEquals("ACTIVE", generationStatus(key, 1));
    }

    @Test
    void aWriterWhoseClaimExpiredTouchesZeroState() throws Exception {
        RepositoryIndexKey key = coordinate();
        jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId,
                WorkProjectId.generate(), key, actor, NOW));
        // The first worker parks inside its first embedding batch; meanwhile its lease
        // is taken away, so every later state write of that pass must be fenced.
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        embeddings.parkInsideFirstBatch(parked, release);
        KnowledgeIndexWorker stalled = worker("worker-a");
        var late = CompletableFuture.supplyAsync(stalled::runOnce);
        assertTrue(parked.await(5, TimeUnit.SECONDS), "the first batch must start");

        expireLease();
        release.countDown();
        var firstResult = late.get(5, TimeUnit.SECONDS);
        assertEquals(1, firstResult.fencedWrites(), "the late pass reports itself fenced");
        assertEquals(0, firstResult.failedJobs());

        // The next claim reclaims the job and finishes it: the late writer's ten vector
        // rows and their honest checkpoint stand (its state transitions were fenced),
        // so the reclaim resumes from the checkpoint — twelve positions, no duplicates.
        var secondResult = worker("worker-b").runOnce();
        assertEquals(1, secondResult.claimedJobs());
        assertEquals(0, secondResult.failedJobs());
        assertEquals(
                KnowledgeIndexJobStatus.READY,
                jobs.findById(organizationId, teamId, jobIdOf(key)).orElseThrow().status());
        assertEquals(FILES, chunkRows());
        assertEquals(FILES, embeddings.embeddedInputs().size(),
                "ten before the fence, only the tail batch rebuilt after it");
    }

    @Test
    void aClosedGateStillDrainsQueuedJobs() {
        KnowledgeIndexJobService openGate = service(true);
        KnowledgeIndexJobService closedGate = service(false);
        KnowledgeEntryId entryId = KnowledgeEntryId.generate();
        seedEntryFor(entryId);
        KnowledgeIndexJob queued = openGate.enqueueEntryRefresh(
                organizationId, teamId, entryId, actor).orElseThrow();

        assertTrue(closedGate.enqueueEntryRefresh(organizationId, teamId, entryId, actor)
                        .isEmpty(),
                "a closed gate accepts no new refresh work");
        KnowledgeIndexJob cleanup = closedGate.enqueueEntryCleanup(
                organizationId, teamId, entryId, actor);
        assertEquals(queued.id(), cleanup.id(),
                "cleanup keeps flowing through the closed gate, idempotent onto the live job");

        KnowledgeEntryVersion version = publishedVersion(entryId);
        when(knowledge.findEffectiveVersion(organizationId, teamId, entryId))
                .thenReturn(Optional.of(version));
        // The cleanup enqueue is idempotent onto the still-live refresh job, so exactly
        // one queued job drains through the closed gate.
        var result = worker("worker-drain").runOnce();

        assertEquals(1, result.claimedJobs());
        assertEquals(0, result.failedJobs());
        assertEquals(
                KnowledgeIndexJobStatus.READY,
                jobs.findById(organizationId, teamId, queued.id()).orElseThrow().status(),
                "the gate guards enqueueing, never the draining of queued jobs");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.knowledge_entry_embedding", Integer.class),
                "one vector row for the effective revision");
    }

    // ------------------------------------------------------------------ fixtures

    private RepositoryIndexKey coordinate() {
        return new RepositoryIndexKey(
                organizationId, teamId, bindingId,
                new SourceCommit("0123456789012345678901234567890123456789"),
                ChunkingPolicy.defaults().policyHash(), MODEL);
    }

    private KnowledgeIndexWorker worker(String workerId) {
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(
                dataSource);
        TransactionExecutor transactions = new SpringTransactionExecutor(transactionManager);
        return new KnowledgeIndexWorker(
                jobs,
                embeddings,
                knowledge,
                knowledgeVectors,
                fixedContent(),
                chunkVectors,
                generations,
                transactions,
                () -> NOW,
                io.crewscope.application.observability.OperationalTelemetry.noop(),
                workerId,
                LEASE,
                GenerationRetentionPolicy.DEFAULT,
                20000,
                ChunkingPolicy.defaults());
    }

    private KnowledgeIndexJobService service(boolean refreshEnabled) {
        return new KnowledgeIndexJobService(
                jobs, embeddings, knowledge, () -> NOW, refreshEnabled);
    }

    private RepositoryContentPort fixedContent() {
        List<RepositoryFileRef> files = IntStream.rangeClosed(1, FILES)
                .mapToObj(i -> new RepositoryFileRef("src/File" + i + ".java", 64))
                .toList();
        return new RepositoryContentPort() {
            @Override
            public List<RepositoryFileRef> listFiles(
                    OrganizationId organization, TeamId team, RepositoryBindingId binding,
                    SourceCommit commit) {
                return files;
            }

            @Override
            public RepositoryFileContent readFile(
                    OrganizationId organization, TeamId team, RepositoryBindingId binding,
                    SourceCommit commit, String path) {
                String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
                return new RepositoryFileContent(path, name + "\n\nclass " + name + " {}\n");
            }
        };
    }

    private List<RepositoryChunkVector> handcraftedVectors(
            RepositoryGenerationKey generation, int first, int last) {
        return IntStream.rangeClosed(first, last)
                .mapToObj(seq -> new RepositoryChunkVector(
                        generation, seq, "src/File" + seq + ".java", "java",
                        1, 2, "a".repeat(64), "old pass", MODEL, filled(0.25f)))
                .toList();
    }

    private KnowledgeEntryVersion publishedVersion(KnowledgeEntryId entryId) {
        return KnowledgeEntryVersion.create(
                entryId,
                new io.crewscope.domain.team.TeamScope(organizationId, teamId),
                new io.crewscope.domain.knowledge.KnowledgeEntryRevision(1),
                Optional.empty(),
                "On-call runbook",
                "Step one",
                actor,
                NOW);
    }

    private UUID jobIdOf(RepositoryIndexKey key) {
        return jdbc.queryForObject(
                "SELECT id FROM crewscope.knowledge_index_job WHERE index_key = ?",
                UUID.class, IndexKeyCodec.hash(key));
    }

    private String generationStatus(RepositoryIndexKey key, int sequence) {
        return jdbc.queryForObject(
                "SELECT status FROM crewscope.repository_index_generation"
                        + " WHERE index_key = ? AND build_sequence = ?",
                String.class, IndexKeyCodec.hash(key), sequence);
    }

    private int chunkRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.repository_chunk_embedding", Integer.class);
    }

    private void expireLease() {
        // Relative to the row's own lease: the fixture clock trails the database clock.
        jdbc.update(
                "UPDATE crewscope.knowledge_index_job"
                        + " SET lease_expires_at = lease_expires_at - interval '2 hours'"
                        + " WHERE status IN ('CHUNKING', 'EMBEDDING', 'ACTIVATING')");
    }

    private static float[] filled(float value) {
        float[] embedding = new float[MODEL.dimension()];
        java.util.Arrays.fill(embedding, value);
        return embedding;
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
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Restart Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Restart Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Restart owner', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
    }

    private void seedEntryFor(KnowledgeEntryId id) {
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status, effective_revision,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, 'PUBLISHED', 1, 1, 0, now(), ?, now(), ?)
                """,
                id.value(), organizationId.value(), teamId.value(),
                "entry-" + id.value(), actor.value(), actor.value());
        // The vector row's cross-chain FK targets the immutable version, not the head.
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry_version (
                    organization_id, team_id, entry_id, revision, title, content,
                    content_hash, created_at, created_by_principal_id)
                VALUES (?, ?, ?, 1, 'On-call runbook', 'Step one', ?, now(), ?)
                """,
                organizationId.value(), teamId.value(), id.value(),
                "c".repeat(64), actor.value());
    }

    /** Records every delivered batch and can park inside the first one. */
    private static final class RecordingEmbeddings implements KnowledgeEmbeddingExecutor {

        private final List<String> embeddedInputs = new ArrayList<>();
        private CountDownLatch parkAt;
        private CountDownLatch resume;

        void parkInsideFirstBatch(CountDownLatch parkAt, CountDownLatch resume) {
            this.parkAt = parkAt;
            this.resume = resume;
        }

        List<String> embeddedInputs() {
            return List.copyOf(embeddedInputs);
        }

        @Override
        public EmbeddingModelRevision resolveModel(
                OrganizationId organizationId, TeamId teamId) {
            return MODEL;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            embeddedInputs.addAll(command.inputs());
            if (parkAt != null) {
                CountDownLatch gate = parkAt;
                parkAt = null;
                gate.countDown();
                try {
                    assertTrue(resume.await(5, TimeUnit.SECONDS),
                            "the parked batch must be released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
            float[][] vectors = new float[command.inputs().size()][MODEL.dimension()];
            for (float[] vector : vectors) {
                java.util.Arrays.fill(vector, 0.25f);
            }
            return new EmbeddingBatchResult(
                    List.of(vectors),
                    MODEL,
                    new io.crewscope.domain.model.ModelCatalogCoordinate(
                            io.crewscope.domain.model.ModelCatalogEntryId.generate(),
                            new io.crewscope.domain.model.ModelProviderKey("dashscope"),
                            new io.crewscope.domain.model.ModelId(MODEL.modelKey()),
                            new io.crewscope.domain.model.ModelCatalogRevision(
                                    MODEL.revision())),
                    io.crewscope.domain.model.ModelConnectionId.generate(),
                    1);
        }
    }
}
