package io.crewscope.infrastructure.persistence.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.application.retrieval.GenerationCatalog;
import io.crewscope.application.retrieval.KnowledgeEmbeddingExecutor;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVector;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.KnowledgeIndexJob;
import io.crewscope.application.retrieval.KnowledgeRetrievalQuery;
import io.crewscope.application.retrieval.KnowledgeRetrievalResult;
import io.crewscope.application.retrieval.KnowledgeRetrievalService;
import io.crewscope.application.retrieval.RepositoryChunkVector;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.application.retrieval.RepositoryGeneration;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.coding.RepositoryBindingScope;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.coding.RepositoryBranchName;
import io.crewscope.domain.coding.RepositoryKey;
import io.crewscope.domain.coding.RepositoryKind;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.GenerationRetentionPolicy;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.infrastructure.persistence.knowledge.PgVectorKnowledgeEmbeddingStore;
import io.crewscope.infrastructure.testcontainers.AbstractPgVectorContainerIntegrationTest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Service-level pgvector proof for unified retrieval (M10-A01): the real
 * {@link KnowledgeRetrievalService} flow over the real vector SQL — the knowledge route
 * rides the effective-version gate and tenant predicates of V2's nearest, the repository
 * route resolves the ACTIVE generation through {@link JdbcGenerationCatalogAdapter} and
 * searches exactly that coordinate, merged spans truncate to top-K after merging, and
 * the frozen HNSW cosine index serves the chunk-side nearest. Guards and binding
 * validation stay in-memory collaborators: their contract is covered by the service
 * unit tests; this test is about the SQL surfaces.
 */
class KnowledgeRetrievalPgVectorIntegrationTest extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final SourceCommit COMMIT =
            new SourceCommit("0123456789012345678901234567890123456789");
    private static final SourceCommit OTHER_COMMIT =
            new SourceCommit("9999999999999999999999999999999999999999");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Retrieval", NOW);
    private final TeamId teamId = initialization.team().id();
    private final PrincipalId actorId = actor.id();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;
    private JdbcRepositoryGenerationStoreAdapter generationWrites;
    private PgVectorKnowledgeEmbeddingStore knowledgeVectors;
    private PgVectorRepositoryChunkStore chunkVectors;
    private GenerationCatalog generations;
    private GuardStore guards;
    private final Map<KnowledgeEntryId, KnowledgeEntryVersion> versions = new LinkedHashMap<>();
    private KnowledgeRetrievalService service;

    @BeforeEach
    void freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PGVECTOR.getJdbcUrl(), PGVECTOR.getUsername(), PGVECTOR.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jobs = new JdbcKnowledgeIndexJobRepositoryAdapter(
                new NamedParameterJdbcTemplate(dataSource));
        generationWrites = new JdbcRepositoryGenerationStoreAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        knowledgeVectors = new PgVectorKnowledgeEmbeddingStore(jdbc);
        chunkVectors = new PgVectorRepositoryChunkStore(jdbc);
        generations = new JdbcGenerationCatalogAdapter(jdbc);
        guards = new GuardStore(initialization);
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedTenant(organizationId, teamId, actorId);
        service = new KnowledgeRetrievalService(
                new FixedVectorExecutor(),
                generations,
                knowledgeVectors,
                chunkVectors,
                new VersionReadingRepository(),
                new SingleBindingRepository(),
                guards,
                guards,
                ChunkingPolicy.defaults(),
                true);
    }

    @Test
    void theKnowledgeRouteRidesTheEffectiveGateAndTenantPredicatesOfTheRealSql() {
        UUID hit = seedEntry("hit", "PUBLISHED", 1L);
        seedVersion(hit, 1L);
        knowledgeVectors.replace(vector(hit, 1, "a".repeat(64), unitVector(0)));
        UUID superseded = seedEntry("superseded", "PUBLISHED", 2L);
        seedVersion(superseded, 1L);
        knowledgeVectors.replace(vector(superseded, 1, "b".repeat(64), unitVector(0)));
        UUID retired = seedEntry("retired", "RETIRED", 1L);
        seedVersion(retired, 1L);
        knowledgeVectors.replace(vector(retired, 1, "c".repeat(64), unitVector(0)));
        UUID neighbor = seedEntry("neighbor", "PUBLISHED", 1L);
        seedVersion(neighbor, 1L);
        knowledgeVectors.replace(vector(neighbor, 1, "d".repeat(64), unitVector(1)));
        OrganizationId strangerOrg = OrganizationId.generate();
        TeamId strangerTeam = TeamId.generate();
        seedTenant(strangerOrg, strangerTeam, PrincipalId.generate());
        UUID stranger = seedEntryFor(strangerOrg, strangerTeam, "stranger", "PUBLISHED", 1L);
        seedVersionFor(strangerOrg, strangerTeam, stranger, 1L);
        knowledgeVectors.replace(new KnowledgeEmbeddingVector(
                strangerOrg, strangerTeam, id(stranger), new KnowledgeEntryRevision(1),
                MODEL, "e".repeat(64), unitVector(0)));

        KnowledgeRetrievalResult result = service.retrieve(
                access(), organizationId, teamId, knowledgeOnly());

        assertEquals(List.of(id(hit), id(neighbor)),
                result.candidates().stream()
                        .map(candidate -> candidate.entry().entryId()).toList(),
                "superseded, retired and cross-tenant rows stay behind the real gate");
        assertEquals(1.0, result.candidates().get(0).score(), 1e-6);
        assertEquals(0.0, result.candidates().get(1).score(), 1e-6);
        assertEquals("Title", result.candidates().get(0).entry().title(),
                "the content projection comes from the version read");
        assertTrue(result.degradations().isEmpty());
    }

    @Test
    void theRepositoryRouteSearchesOnlyTheActiveGeneration() {
        // Generation one of the live coordinate: opened and activated.
        RepositoryIndexKey liveKey = indexKey(COMMIT);
        RepositoryGenerationKey live = activate(openGeneration(liveKey));
        chunkVectors.replaceBatch(live, List.of(chunk(live, 1, "docs/live.md", unitVector(0))));

        // Generation two of the same coordinate retires generation one and its vectors:
        // only the new build's rows answer from now on.
        RepositoryGenerationKey next = activate(openGeneration(liveKey));
        chunkVectors.replaceBatch(next, List.of(chunk(next, 1, "docs/next.md", unitVector(0))));

        KnowledgeRetrievalResult result = service.retrieve(
                access(), organizationId, teamId, repositoryOnly(COMMIT));
        assertEquals(List.of("docs/next.md"),
                result.candidates().stream()
                        .map(candidate -> candidate.fragments().get(0).path()).toList(),
                "a retired generation never surfaces through the catalog gate");
        assertTrue(result.degradations().isEmpty());

        // A still-BUILDING generation of a different commit never surfaces either: the
        // catalog answers empty and the source degrades instead of falling back.
        RepositoryIndexKey buildingKey = indexKey(OTHER_COMMIT);
        RepositoryGenerationKey building = openGeneration(buildingKey).generationKey();
        chunkVectors.replaceBatch(building,
                List.of(chunk(building, 1, "docs/draft.md", unitVector(0))));

        KnowledgeRetrievalResult draft = service.retrieve(
                access(), organizationId, teamId, repositoryOnly(OTHER_COMMIT));
        assertTrue(draft.candidates().isEmpty());
        assertEquals(List.of(DegradationReasonCode.NO_MATCHING_GENERATION),
                draft.degradations());
    }

    @Test
    void adjacentSpansMergeIntoOneCandidateBeforeRanking() {
        RepositoryIndexKey key = indexKey(COMMIT);
        RepositoryGenerationKey generation = activate(openGeneration(key));
        // chunk_seq is unique per generation (the vector row's PK leg), so the spans
        // number 1..3 across files even though each file owns a fresh window.
        chunkVectors.replaceBatch(generation, List.of(
                chunk(generation, 1, "docs/adjacent.md", unitVector(0)),
                span(generation, 2, "docs/adjacent.md", 11, 20, unitVector(0)),
                span(generation, 3, "docs/other.md", 51, 60, unitVector(1))));

        KnowledgeRetrievalResult result = service.retrieve(
                access(), organizationId, teamId, repositoryOnly(COMMIT, 3));

        assertEquals(2, result.candidates().size(),
                "three spans of two files fold into two candidates before ranking");
        assertEquals("docs/adjacent.md",
                result.candidates().get(0).fragments().get(0).path());
        assertEquals(2, result.candidates().get(0).fragments().size(),
                "adjacent spans of one file stay visible inside the single candidate");
        assertEquals(List.of(1, 11),
                result.candidates().get(0).fragments().stream()
                        .map(fragment -> fragment.startLine()).toList());
        assertEquals(1, result.candidates().get(1).fragments().size());
    }

    @Test
    void theChunkNearestNeverPlansASequentialScan() {
        RepositoryIndexKey key = indexKey(COMMIT);
        RepositoryGenerationKey generation = activate(openGeneration(key));
        chunkVectors.replaceBatch(generation, List.of(
                chunk(generation, 1, "docs/indexed.md", unitVector(0))));

        List<String> plan = jdbc.queryForList(
                """
                EXPLAIN SELECT chunk_seq FROM crewscope.repository_chunk_embedding
                WHERE index_key = ? AND build_sequence = ? AND model_key = ? AND model_revision = ?
                ORDER BY embedding <=> CAST(? AS public.vector) LIMIT 5
                """,
                String.class,
                IndexKeyCodec.hash(key), generation.buildSequence(),
                MODEL.modelKey(), MODEL.revision(),
                vectorLiteral(unitVector(0)));
        String rendered = String.join("\n", plan);
        // The generation predicate narrows to one coordinate first; whether the planner
        // then picks the frozen HNSW cosine index or an in-generation PK scan with a
        // top-N sort is a cost decision, but a sequential full-table scan never is:
        // the authorized search must stay index-driven at any corpus size.
        assertTrue(!rendered.contains("Seq Scan"),
                "the chunk nearest must stay index-driven: " + rendered);
        assertTrue(rendered.contains("Index Scan"),
                "an index (PK coordinate or HNSW) must drive the chunk nearest: " + rendered);
    }

    // ------------------------------------------------------------------ fixtures

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private KnowledgeRetrievalQuery knowledgeOnly() {
        return new KnowledgeRetrievalQuery(
                "onboarding", Set.of(ManifestSourceType.KNOWLEDGE_ENTRY), null, 8);
    }

    private KnowledgeRetrievalQuery repositoryOnly(SourceCommit commit) {
        return repositoryOnly(commit, 8);
    }

    private KnowledgeRetrievalQuery repositoryOnly(SourceCommit commit, int topK) {
        return new KnowledgeRetrievalQuery(
                "how do we deploy", Set.of(ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, bindingId, commit),
                topK);
    }

    private RepositoryIndexKey indexKey(SourceCommit commit) {
        return new RepositoryIndexKey(
                organizationId, teamId, bindingId, commit,
                ChunkingPolicy.defaults().policyHash(), MODEL);
    }

    /** Retires the coordinate's live job, then opens a fresh BUILDING generation. */
    private RepositoryGeneration openGeneration(RepositoryIndexKey key) {
        jobs.findLiveByIndexKey(key).ifPresent(this::retire);
        UUID job = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, teamId, projectId,
                key, actorId, NOW)).id();
        return generationWrites.open(key, job);
    }

    private void retire(KnowledgeIndexJob live) {
        // The jobs of this test never overlap in time across coordinates, so the
        // global claim is the coordinate's own live job.
        KnowledgeIndexJob claim = jobs.claimNext("retrieval-test", NOW, LEASE).orElseThrow();
        assertEquals(live.id(), claim.id(), "the only live job of the coordinate is claimed");
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        assertTrue(jobs.updateClaimed(
                claim.failed("SUPERSEDED", done), "retrieval-test", done, LEASE).isPresent());
    }

    private RepositoryGenerationKey activate(RepositoryGeneration opened) {
        generationWrites.activate(
                opened.generationKey(), GenerationRetentionPolicy.DEFAULT, actorId);
        return opened.generationKey();
    }

    private RepositoryChunkVector chunk(
            RepositoryGenerationKey generation, int chunkSeq, String path, float[] embedding) {
        return span(generation, chunkSeq, path, 1, 10, embedding);
    }

    private RepositoryChunkVector span(
            RepositoryGenerationKey generation, int chunkSeq, String path,
            int startLine, int endLine, float[] embedding) {
        return new RepositoryChunkVector(
                generation, chunkSeq, path, "markdown", startLine, endLine,
                "a".repeat(64), "span " + chunkSeq + " of " + path, MODEL, embedding);
    }

    private void seedTenant(OrganizationId org, TeamId team, PrincipalId owner) {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Retrieval Org', 'ACTIVE')",
                org.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Retrieval Team', 'ACTIVE')",
                team.value(), org.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Retrieval owner', 'ACTIVE')
                """,
                owner.value(), org.value());
    }

    private UUID seedEntry(String entryKey, String status, Long effectiveRevision) {
        return seedEntryFor(organizationId, teamId, entryKey, status, effectiveRevision);
    }

    private UUID seedEntryFor(
            OrganizationId org, TeamId team, String entryKey, String status, Long effectiveRevision) {
        UUID entryId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status, effective_revision,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, now(), ?, now(), ?)
                """,
                entryId, org.value(), team.value(), entryKey, status, effectiveRevision,
                effectiveRevision == null ? 0L : effectiveRevision, actorId.value(), actorId.value());
        return entryId;
    }

    private void seedVersion(UUID entryId, long revision) {
        seedVersionFor(organizationId, teamId, entryId, revision);
    }

    private void seedVersionFor(OrganizationId org, TeamId team, UUID entryId, long revision) {
        String hash = UUID.nameUUIDFromBytes(
                        ("content-" + entryId + "-" + revision).getBytes())
                .toString().replace("-", "") + "0".repeat(32);
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry_version (
                    organization_id, team_id, entry_id, revision, previous_revision,
                    title, content, content_hash, created_at, created_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 'Title', 'Content', ?, now(), ?)
                """,
                org.value(), team.value(), entryId, revision,
                revision == 1 ? null : revision - 1, hash, actorId.value());
        versions.put(id(entryId), KnowledgeEntryVersion.create(
                id(entryId),
                new TeamScope(org, team),
                new KnowledgeEntryRevision(revision),
                revision == 1
                        ? Optional.empty()
                        : Optional.of(new KnowledgeEntryRevision(revision - 1)),
                "Title", "Content", actorId, NOW));
    }

    private KnowledgeEmbeddingVector vector(
            UUID entryId, long revision, String contentHash, float[] embedding) {
        return new KnowledgeEmbeddingVector(
                organizationId, teamId, id(entryId), new KnowledgeEntryRevision(revision),
                MODEL, contentHash, embedding);
    }

    private static KnowledgeEntryId id(UUID value) {
        return new KnowledgeEntryId(value);
    }

    /** Unit vector on one axis: axis 0 aligns with the fixed query, orthogonal otherwise. */
    private static float[] unitVector(int axis) {
        float[] vector = new float[MODEL.dimension()];
        vector[axis] = 1.0f;
        return vector;
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            literal.append(i == 0 ? "" : ",").append(embedding[i]);
        }
        return literal.append("]").toString();
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

    /** Embedding seam pinned to axis 0: every query geometry is deterministic. */
    private final class FixedVectorExecutor implements KnowledgeEmbeddingExecutor {
        @Override
        public EmbeddingModelRevision resolveModel(OrganizationId org, TeamId team) {
            return MODEL;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            return new EmbeddingBatchResult(
                    List.of(unitVector(0)),
                    MODEL,
                    new ModelCatalogCoordinate(
                            ModelCatalogEntryId.generate(),
                            new ModelProviderKey("dashscope"),
                            new ModelId(MODEL.modelKey()),
                            new ModelCatalogRevision(MODEL.revision())),
                    ModelConnectionId.generate(),
                    1);
        }
    }

    /** The version read only; every other read fails the test loudly. */
    private final class VersionReadingRepository implements KnowledgeRepository {
        @Override
        public KnowledgeEntry create(KnowledgeEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntry save(
                KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findById(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findByKey(
                OrganizationId org, TeamId team, KnowledgeEntryKey entryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryPage findByTeam(
                OrganizationId org, TeamId team,
                KnowledgeEntryFilter filter, KnowledgeEntryPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findVersion(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            KnowledgeEntryVersion version = versions.get(entryId);
            return version != null && version.revision().equals(revision)
                    ? Optional.of(version) : Optional.empty();
        }

        @Override
        public KnowledgeEntryVersionPage findVersionHistory(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId,
                KnowledgeVersionPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findEffectiveVersion(
                OrganizationId org, TeamId team, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId org, TeamId team) {
            throw new UnsupportedOperationException();
        }
    }

    /** One ACTIVE binding at the fixed four coordinates. */
    private final class SingleBindingRepository implements RepositoryBindingRepository {
        @Override
        public RepositoryBinding create(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RepositoryBinding update(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RepositoryBinding> findById(
                OrganizationId org, TeamId team, WorkProjectId project, RepositoryBindingId id) {
            return org.equals(organizationId) && team.equals(teamId)
                    && project.equals(projectId) && id.equals(bindingId)
                    ? Optional.of(RepositoryBinding.reconstitute(
                            bindingId,
                            new RepositoryBindingScope(
                                    organizationId, teamId, WorkspaceId.generate(), projectId),
                            RepositoryKind.LOCAL_MANAGED,
                            new RepositoryKey("repo-" + bindingId.value()),
                            new RepositoryBranchName("main"),
                            RepositoryBindingStatus.ACTIVE,
                            0,
                            AuditMetadata.createdBy(actorId, NOW)))
                    : Optional.empty();
        }

        @Override
        public Optional<RepositoryBinding> findByKey(
                OrganizationId org, TeamId team, WorkProjectId project, RepositoryKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RepositoryBinding> findByWorkProject(
                OrganizationId org, TeamId team, WorkProjectId project) {
            throw new UnsupportedOperationException();
        }
    }

    /** The guard collaborator pair over the initialized Team. */
    private static final class GuardStore implements TeamRepository, TeamMembershipQuery {
        private final TeamInitialization initialization;
        private final List<TeamMember> members;

        private GuardStore(TeamInitialization initialization) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
        }

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId org, TeamId id) {
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(org) && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(OrganizationId org, TeamId id) {
            return Optional.empty();
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId org, TeamId team) {
            return members;
        }
    }
}
