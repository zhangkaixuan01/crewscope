package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.retrieval.GenerationSnapshot;
import io.crewscope.application.retrieval.KnowledgeEmbeddingExecutor;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeIndexJobService;
import io.crewscope.application.retrieval.KnowledgeIndexJobStatus;
import io.crewscope.application.retrieval.KnowledgeIndexWorker;
import io.crewscope.application.transaction.TransactionExecutor;
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
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.git.GitCommandPolicy;
import io.crewscope.infrastructure.workspace.repository.GitRepositoryContentAdapter;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Full-chain PostgreSQL lifecycle for repository index generations (M10-I01b): a real
 * bare Git repository flows enqueue → chunk → embed → activate → catalog, a second
 * build of the same coordinate rolls the activation pointer while retention keeps both
 * generations with their vectors, and a failed build (a drifted model here) never
 * disturbs the ACTIVE generation.
 */
@Tag("integration")
class RepositoryIndexGenerationLifecycleIntegrationTest
        extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-03T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);

    @TempDir
    Path temporaryDirectory;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;
    private JdbcRepositoryGenerationStoreAdapter generations;
    private JdbcGenerationCatalogAdapter catalog;
    private PgVectorRepositoryChunkStore chunkVectors;
    private DriftingEmbeddings embeddings;
    private KnowledgeIndexJobService service;

    @BeforeEach
    void freshDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        PlatformTransactionManager transactions0 = new DataSourceTransactionManager(dataSource);
        generations = new JdbcRepositoryGenerationStoreAdapter(
                new NamedParameterJdbcTemplate(dataSource), transactions0);
        catalog = new JdbcGenerationCatalogAdapter(jdbc);
        chunkVectors = new PgVectorRepositoryChunkStore(jdbc);
        embeddings = new DriftingEmbeddings();
        service = new KnowledgeIndexJobService(
                jobs, embeddings, mock(KnowledgeRepository.class), () -> NOW, true);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedTenantAndBinding();
    }

    @Test
    void aSmallRepositoryBuildsThroughToAnActiveGeneration() throws Exception {
        requireGit();
        RepositoryIndexKey key = buildOnce();

        Optional<GenerationSnapshot> active = catalog.findActiveGeneration(key);
        assertTrue(active.isPresent(), "the finished build must be the ACTIVE generation");
        assertEquals(1, active.orElseThrow().generationKey().buildSequence());
        assertEquals(3, chunkRows(), "README, guide and Main each become one chunk");
        assertEquals(
                3, embeddings.embeddedInputs().size(),
                "every chunk reached the embedding seam exactly once");
    }

    @Test
    void aSecondBuildRollsThePointerAndKeepsBothGenerations() throws Exception {
        requireGit();
        RepositoryIndexKey key = buildOnce();

        KnowledgeIndexJob second = service.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId,
                key.sourceCommit(), actor).orElseThrow();
        worker().runOnce();

        assertEquals(
                KnowledgeIndexJobStatus.READY,
                jobs.findById(organizationId, teamId, second.id()).orElseThrow().status());
        Optional<GenerationSnapshot> active = catalog.findActiveGeneration(key);
        assertEquals(2, active.orElseThrow().generationKey().buildSequence(),
                "the pointer rolls forward");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.repository_index_generation"
                        + " WHERE index_key = ?",
                Integer.class, IndexKeyCodec.hash(key)),
                "retention keeps both generations");
        assertEquals(6, chunkRows(), "both generations keep their vectors");
        assertEquals("RETIRED", generationStatus(key, 1));
        assertEquals("ACTIVE", generationStatus(key, 2));
    }

    @Test
    void aFailedBuildNeverDisturbsTheActiveGeneration() throws Exception {
        requireGit();
        RepositoryIndexKey key = buildOnce();
        embeddings.driftFromSecondBuild();

        KnowledgeIndexJob failed = service.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId,
                key.sourceCommit(), actor).orElseThrow();
        var result = worker().runOnce();

        assertEquals(1, result.claimedJobs());
        assertEquals(1, result.failedJobs(), "the drifted model fails the build");
        KnowledgeIndexJob job = jobs.findById(organizationId, teamId, failed.id()).orElseThrow();
        assertEquals(KnowledgeIndexJobStatus.FAILED, job.status());
        assertEquals(Optional.of("MODEL_DRIFT"), job.failureCode());
        assertEquals(2, generationCount(key));
        assertEquals("FAILED", generationStatus(key, 2), "the broken build marks only itself");
        assertEquals(1, catalog.findActiveGeneration(key)
                .orElseThrow().generationKey().buildSequence(),
                "the previous ACTIVE generation survives untouched");
    }

    // ------------------------------------------------------------------ flow

    /** Enqueues and runs one build to READY; returns the coordinate it indexed. */
    private RepositoryIndexKey buildOnce() throws Exception {
        SourceCommit commit = fixture().firstCommit();
        KnowledgeIndexJob job = service.enqueueRepositoryBuild(
                organizationId, teamId, projectId, bindingId, commit, actor).orElseThrow();
        worker().runOnce();
        return job.indexKey().orElseThrow();
    }

    private KnowledgeIndexWorker worker() {
        GitCommandExecutor gitCommands = new GitCommandExecutor(
                new GitCommandPolicy(fixtureHome(), Duration.ofSeconds(10), 16 * 1024 * 1024));
        ManagedRepositoryResolver resolver = new ManagedRepositoryResolver(
                fixtureCache.managedRoot(), ownerOfManagedRoot(), gitCommands);
        GitRepositoryContentAdapter content = new GitRepositoryContentAdapter(
                new NamedParameterJdbcTemplate(dataSource), resolver, gitCommands);
        return new KnowledgeIndexWorker(
                jobs,
                embeddings,
                mock(KnowledgeRepository.class),
                mock(KnowledgeEmbeddingVectorStore.class),
                content,
                chunkVectors,
                generations,
                new SpringTransactionExecutor(new DataSourceTransactionManager(dataSource)),
                () -> NOW,
                io.crewscope.application.observability.OperationalTelemetry.noop(),
                "lifecycle-worker",
                LEASE,
                GenerationRetentionPolicy.DEFAULT,
                20000,
                ChunkingPolicy.defaults());
    }

    // ------------------------------------------------------------------ fixtures

    private Fixture fixtureCache;

    private Fixture fixture() throws Exception {
        if (fixtureCache == null) {
            fixtureCache = Fixture.create(temporaryDirectory.resolve("lifecycle"));
        }
        return fixtureCache;
    }

    private Path fixtureHome() {
        return temporaryDirectory.resolve("lifecycle").resolve("home");
    }

    private String ownerOfManagedRoot() {
        try {
            return Files.getOwner(fixture().managedRoot()).getName();
        } catch (Exception failure) {
            throw new IllegalStateException("managed root owner unavailable", failure);
        }
    }

    private record Fixture(SourceCommit firstCommit, Path managedRoot) {

        static Fixture create(Path root) throws Exception {
            Files.createDirectories(root);
            Path source = root.resolve("source");
            Path managedRoot = root.resolve("managed");
            Path bare = managedRoot.resolve("demo-repo.git");
            Files.createDirectories(managedRoot);
            run(root, "git", "init", "--initial-branch=main", source.toString());
            run(source, "git", "config", "user.name", "I01b Lifecycle");
            run(source, "git", "config", "user.email", "fixture@crewscope.local");
            Files.writeString(source.resolve("README.md"), "# Demo\n\nOne heading.\n");
            Files.createDirectories(source.resolve("docs"));
            Files.writeString(source.resolve("docs/guide.md"), "# Guide\n\nUse it.\n");
            Files.createDirectories(source.resolve("src"));
            Files.writeString(source.resolve("src/Main.java"), "class Main {}\n");
            run(source, "git", "add", "--all");
            run(source, "git", "commit", "-m", "first");
            SourceCommit first =
                    new SourceCommit(run(source, "git", "rev-parse", "HEAD").trim());
            run(root, "git", "init", "--bare", bare.toString());
            run(source, "git", "push", bare.toString(), "HEAD:refs/heads/main");
            return new Fixture(first, managedRoot);
        }
    }

    private static String run(Path workingDirectory, String... command) throws Exception {
        Process process = new ProcessBuilder(List.of(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fixture command timed out");
        assertTrue(process.exitValue() == 0, output);
        return output;
    }

    private static void requireGit() throws Exception {
        Process process = new ProcessBuilder("git", "--version").start();
        Assumptions.assumeTrue(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0);
    }

    private String generationStatus(RepositoryIndexKey key, int sequence) {
        return jdbc.queryForObject(
                "SELECT status FROM crewscope.repository_index_generation"
                        + " WHERE index_key = ? AND build_sequence = ?",
                String.class, IndexKeyCodec.hash(key), sequence);
    }

    private int generationCount(RepositoryIndexKey key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.repository_index_generation"
                        + " WHERE index_key = ?",
                Integer.class, IndexKeyCodec.hash(key));
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

    private void seedTenantAndBinding() {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Lifecycle Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Lifecycle Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Lifecycle owner', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
        UUID workspaceId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.workspace (id, organization_id, team_id, workspace_type, name, status)
                VALUES (?, ?, ?, 'TEAM', 'Lifecycle workspace', 'ACTIVE')
                """,
                workspaceId, organizationId.value(), teamId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.work_project (id, organization_id, team_id, workspace_id, project_key, name)
                VALUES (?, ?, ?, ?, 'lifecycl', 'Lifecycle project')
                """,
                projectId.value(), organizationId.value(), teamId.value(), workspaceId);
        jdbc.update(
                """
                INSERT INTO crewscope.repository_binding (
                    id, organization_id, team_id, workspace_id, project_id,
                    repository_kind, repository_key, default_branch, status,
                    created_by_principal_id, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 'LOCAL_MANAGED', 'demo-repo', 'main', 'ACTIVE', ?, ?)
                """,
                bindingId.value(), organizationId.value(), teamId.value(), workspaceId,
                projectId.value(), actor.value(), actor.value());
    }

    /** Delivers fixed vectors; can drift its model from the second build on. */
    private static final class DriftingEmbeddings implements KnowledgeEmbeddingExecutor {

        private final List<String> embeddedInputs = new java.util.ArrayList<>();
        private boolean drifted;

        void driftFromSecondBuild() {
            drifted = true;
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
            EmbeddingModelRevision model =
                    drifted ? new EmbeddingModelRevision(MODEL.modelKey(), 1024, 99) : MODEL;
            float[][] vectors = new float[command.inputs().size()][model.dimension()];
            for (float[] vector : vectors) {
                java.util.Arrays.fill(vector, 0.25f);
            }
            return new EmbeddingBatchResult(
                    List.of(vectors),
                    model,
                    new io.crewscope.domain.model.ModelCatalogCoordinate(
                            io.crewscope.domain.model.ModelCatalogEntryId.generate(),
                            new io.crewscope.domain.model.ModelProviderKey("dashscope"),
                            new io.crewscope.domain.model.ModelId(model.modelKey()),
                            new io.crewscope.domain.model.ModelCatalogRevision(
                                    model.revision())),
                    io.crewscope.domain.model.ModelConnectionId.generate(),
                    1);
        }
    }
}
