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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * M10-Q01 fixed cross-team attack set over the real vector SQL (S01 §4's frozen proof
 * duty, deferred to this package): two teams of one organization hold deliberately
 * similar knowledge entries and identically-pathed repository chunks — same embedding
 * axis, near-identical content — and every authorized query must answer only the
 * attacking team's rows. The frozen dual assertion runs on both routes: the scoped
 * result stays inside the attacking team's id set, while the same nearest-neighbour
 * SQL without the tenant predicate immediately surfaces the neighbour's rows — the
 * isolation is proven to live in the predicate, not in a data coincidence. A
 * zero-match query proves the scope never widens: with the only perfect matches in
 * the table sitting in the neighbour tenant, the authorized answer is empty.
 */
class CrossTeamVectorStoresFixedAttackSetM10Q01Test extends AbstractPgVectorContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final SourceCommit COMMIT =
            new SourceCommit("0123456789012345678901234567890123456789");
    private static final List<String> ATTACK_PATHS = List.of(
            "docs/deploy.md", "docs/review.md", "docs/rollback.md", "docs/oncall.md");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Alpha owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Alpha", NOW);
    private final TeamId teamAlpha = initialization.team().id();
    private final PrincipalId actorId = actor.id();
    /** The neighbour team of the same organization: the closest possible stranger. */
    private final TeamId teamBeta = TeamId.generate();
    private final PrincipalId betaOwner = PrincipalId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final RepositoryBindingId bindingAlpha = RepositoryBindingId.generate();
    private final RepositoryBindingId bindingBeta = RepositoryBindingId.generate();
    private final Map<KnowledgeEntryId, KnowledgeEntryVersion> versions = new LinkedHashMap<>();

    private JdbcTemplate jdbc;
    private JdbcKnowledgeIndexJobRepositoryAdapter jobs;
    private JdbcRepositoryGenerationStoreAdapter generationWrites;
    private PgVectorKnowledgeEmbeddingStore knowledgeVectors;
    private PgVectorRepositoryChunkStore chunkVectors;
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
        jdbc.execute("DROP SCHEMA IF EXISTS crewscope CASCADE");
        jdbc.execute("DROP EXTENSION IF EXISTS vector CASCADE");
        migrateDefaultChain();
        migrateVectorChain();
        seedOrganization();
        seedTenant(teamAlpha, actorId);
        seedTenant(teamBeta, betaOwner);
        GuardStore guards = new GuardStore(initialization);
        service = new KnowledgeRetrievalService(
                new FixedVectorExecutor(),
                new JdbcGenerationCatalogAdapter(jdbc),
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
    void theKnowledgeRouteAnswersOnlyTheAttackingTeamDespiteIdenticalGeometry() {
        // Six pairs of near-identical entries: same axis, near-identical content, one
        // team-identifying word apart — exactly the similarity S01 §4 froze this set for.
        List<UUID> alpha = seedKnowledge(teamAlpha, "alpha", "a");
        List<UUID> beta = seedKnowledge(teamBeta, "beta", "1");

        KnowledgeRetrievalResult result = service.retrieve(
                access(), organizationId, teamAlpha, knowledgeOnly());

        Set<UUID> returned = result.candidates().stream()
                .map(candidate -> candidate.entry().entryId().value())
                .collect(Collectors.toSet());
        assertEquals(Set.copyOf(alpha), returned,
                "every candidate belongs to the attacking team, all six of them");
        assertTrue(returned.stream().noneMatch(beta::contains),
                "the neighbour's perfectly-aligned rows never surface");
        assertTrue(result.degradations().isEmpty());

        // Control assertion of the frozen proof: the same nearest-neighbour ordering
        // without the tenant predicate immediately mixes both teams in.
        List<UUID> unscoped = jdbc.queryForList(
                """
                SELECT entry_id FROM crewscope.knowledge_entry_embedding
                WHERE model_key = ? AND model_revision = ?
                ORDER BY embedding <=> CAST(? AS public.vector)
                """,
                UUID.class,
                MODEL.modelKey(), MODEL.revision(),
                vectorLiteral(unitVector(0)));
        assertEquals(alpha.size() + beta.size(), unscoped.size(),
                "the unscoped SQL sees every tenant's row — only the predicate protects");
        assertTrue(unscoped.containsAll(beta),
                "the neighbour's rows are right there one predicate away");
    }

    @Test
    void theRepositoryRouteAnswersOnlyTheAttackingTeamsGeneration() {
        // Identical paths in both teams' active generations; the content differs by
        // exactly one team-identifying word, so the hashes stay team-distinct.
        Set<String> alphaHashes = seedChunks(teamAlpha, bindingAlpha, "a");
        Set<String> betaHashes = seedChunks(teamBeta, bindingBeta, "5");

        KnowledgeRetrievalResult result = service.retrieve(
                access(), organizationId, teamAlpha, repositoryOnly());

        List<String> returnedHashes = result.candidates().stream()
                .flatMap(candidate -> candidate.fragments().stream())
                .map(fragment -> fragment.contentHash())
                .toList();
        assertEquals(ATTACK_PATHS.size(), returnedHashes.size());
        assertTrue(alphaHashes.containsAll(returnedHashes),
                "every fragment comes from the attacking team's active generation");
        assertTrue(returnedHashes.stream().noneMatch(betaHashes::contains),
                "the neighbour's identically-pathed chunks never surface");
        assertTrue(result.degradations().isEmpty());

        // Control assertion: without the generation coordinate (which embeds the tenant)
        // the chunk table answers both teams' rows — the coordinate is the isolation.
        List<UUID> unscopedTeams = jdbc.queryForList(
                """
                SELECT DISTINCT team_id FROM crewscope.repository_chunk_embedding
                WHERE model_key = ? AND model_revision = ?
                """,
                UUID.class,
                MODEL.modelKey(), MODEL.revision());
        assertEquals(
                Set.of(teamAlpha.value(), teamBeta.value()),
                Set.copyOf(unscopedTeams),
                "the unscoped SQL sees both tenants — only the coordinate protects");
    }

    @Test
    void aZeroMatchQueryStaysEmptyInsteadOfWideningScope() {
        // The attacking team owns head rows but no vectors, so nothing of its own can
        // match; the only perfect matches in the whole table sit in the neighbour team.
        seedKnowledge(teamBeta, "beta", "1");
        seedKnowledgeHeads(teamAlpha, "alpha");

        KnowledgeRetrievalResult result = service.retrieve(
                access(), organizationId, teamAlpha, knowledgeOnly());

        assertTrue(result.candidates().isEmpty(),
                "an empty authorized answer must stay empty — never widen to the neighbour");
        Long neighbourVectors = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crewscope.knowledge_entry_embedding WHERE team_id = ?",
                Long.class, teamBeta.value());
        assertEquals(6L, neighbourVectors,
                "the control: perfect matches exist in the table, one predicate away");
    }

    // ------------------------------------------------------------------ fixtures

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private KnowledgeRetrievalQuery knowledgeOnly() {
        return new KnowledgeRetrievalQuery(
                "onboarding", Set.of(ManifestSourceType.KNOWLEDGE_ENTRY), null, 8);
    }

    private KnowledgeRetrievalQuery repositoryOnly() {
        return new KnowledgeRetrievalQuery(
                "how do we deploy", Set.of(ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, bindingAlpha, COMMIT), 8);
    }

    /** Seeds six PUBLISHED entries with versions and axis-0 vectors for one team. */
    private List<UUID> seedKnowledge(TeamId team, String keyPrefix, String hashChar) {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            char hashLetter = (char) (hashChar.charAt(0) + index);
            UUID entryId = seedEntry(team, keyPrefix + "-entry-" + index, "PUBLISHED");
            seedVersion(team, entryId, 1);
            knowledgeVectors.replace(new KnowledgeEmbeddingVector(
                    organizationId, team, id(entryId), new KnowledgeEntryRevision(1),
                    MODEL, String.valueOf(hashLetter).repeat(64), unitVector(0)));
            ids.add(entryId);
        }
        return ids;
    }

    /** Head and version rows without vectors: present in the authority, absent from search. */
    private void seedKnowledgeHeads(TeamId team, String keyPrefix) {
        for (int index = 0; index < 6; index++) {
            UUID entryId = seedEntry(team, keyPrefix + "-entry-" + index, "PUBLISHED");
            seedVersion(team, entryId, 1);
        }
    }

    /** Opens and activates one generation of the team's coordinate, seeding four chunks. */
    private Set<String> seedChunks(TeamId team, RepositoryBindingId binding, String hashChar) {
        RepositoryIndexKey key = new RepositoryIndexKey(
                organizationId, team, binding, COMMIT,
                ChunkingPolicy.defaults().policyHash(), MODEL);
        jobs.findLiveByIndexKey(key).ifPresent(this::retire);
        UUID job = jobs.create(KnowledgeIndexJob.repositoryBuild(
                UUID.randomUUID(), organizationId, team, projectId,
                key, actorId, NOW)).id();
        RepositoryGeneration opened = generationWrites.open(key, job);
        generationWrites.activate(
                opened.generationKey(), GenerationRetentionPolicy.DEFAULT, actorId);
        Set<String> hashes = new LinkedHashSet<>();
        List<RepositoryChunkVector> chunks = new ArrayList<>();
        for (int index = 0; index < ATTACK_PATHS.size(); index++) {
            String hash = String.valueOf((char) (hashChar.charAt(0) + index)).repeat(64);
            hashes.add(hash);
            chunks.add(chunk(opened.generationKey(), index + 1, ATTACK_PATHS.get(index), hash));
        }
        chunkVectors.replaceBatch(opened.generationKey(), chunks);
        return hashes;
    }

    private void retire(KnowledgeIndexJob live) {
        // Generations never overlap in time across coordinates here, so the global
        // claim is the coordinate's own live job.
        KnowledgeIndexJob claim = jobs.claimNext("retrieval-test", NOW, LEASE).orElseThrow();
        assertEquals(live.id(), claim.id(), "the only live job of the coordinate is claimed");
        UtcTimestamp done = UtcTimestamp.from(NOW.value().plusSeconds(30));
        assertTrue(jobs.updateClaimed(
                claim.failed("SUPERSEDED", done), "retrieval-test", done, LEASE).isPresent());
    }

    private RepositoryChunkVector chunk(
            RepositoryGenerationKey generation, int chunkSeq, String path, String contentHash) {
        return new RepositoryChunkVector(
                generation, chunkSeq, path, "markdown", 1, 10,
                contentHash, "span " + chunkSeq + " of " + path, MODEL, unitVector(0));
    }

    private void seedOrganization() {
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Attack Org', 'ACTIVE')",
                organizationId.value());
    }

    private void seedTenant(TeamId team, PrincipalId owner) {
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                team.value(), organizationId.value(), team.equals(teamAlpha) ? "Alpha" : "Beta");
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', ?, 'ACTIVE')
                """,
                owner.value(), organizationId.value(),
                team.equals(teamAlpha) ? "Alpha owner" : "Beta owner");
    }

    private UUID seedEntry(TeamId team, String entryKey, String status) {
        UUID entryId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry (
                    id, organization_id, team_id, entry_key, status, effective_revision,
                    latest_revision, version, created_at, created_by_principal_id,
                    updated_at, updated_by_principal_id)
                VALUES (?, ?, ?, ?, ?, 1, 1, 0, now(), ?, now(), ?)
                """,
                entryId, organizationId.value(), team.value(), entryKey, status,
                actorId.value(), actorId.value());
        return entryId;
    }

    private void seedVersion(TeamId team, UUID entryId, long revision) {
        String hash = UUID.nameUUIDFromBytes(
                        ("attack-" + entryId + "-" + revision).getBytes())
                .toString().replace("-", "") + "0".repeat(32);
        jdbc.update(
                """
                INSERT INTO crewscope.knowledge_entry_version (
                    organization_id, team_id, entry_id, revision, previous_revision,
                    title, content, content_hash, created_at, created_by_principal_id)
                VALUES (?, ?, ?, ?, NULL, 'Title', 'Content', ?, now(), ?)
                """,
                organizationId.value(), team.value(), entryId, revision, hash, actorId.value());
        versions.put(id(entryId), KnowledgeEntryVersion.create(
                id(entryId),
                new TeamScope(organizationId, team),
                new KnowledgeEntryRevision(revision),
                Optional.empty(),
                "Title", "Content", actorId, NOW));
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

    /** One ACTIVE binding at the attacking team's fixed four coordinates. */
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
            return org.equals(organizationId) && team.equals(teamAlpha)
                    && project.equals(projectId) && id.equals(bindingAlpha)
                    ? Optional.of(RepositoryBinding.reconstitute(
                            bindingAlpha,
                            new RepositoryBindingScope(
                                    organizationId, teamAlpha, WorkspaceId.generate(), projectId),
                            RepositoryKind.LOCAL_MANAGED,
                            new RepositoryKey("repo-" + bindingAlpha.value()),
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

    /** The guard collaborator pair over the attacking team. */
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
