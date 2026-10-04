package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.EmbeddingDeliveryException;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
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
import io.crewscope.domain.model.ModelConnectionHealthFailureCode;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Service contract of unified retrieval (M10-A01, S01 §3.4): the member-level guard, the
 * two explicit degradation codes (switch or collaborator absent, embedding undeliverable)
 * versus the source-scoped NO_MATCHING_GENERATION, the explainable three-key order, the
 * adjacent same-file merge with its three span relations, the post-merge top-K ceiling,
 * and the pinned repository coordinate — all against deterministic fake vectors.
 */
final class KnowledgeRetrievalServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final EmbeddingModelRevision MODEL =
            new EmbeddingModelRevision("text-embedding-v4", 1024, 3);
    private static final SourceCommit COMMIT =
            new SourceCommit("0123456789012345678901234567890123456789");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Platform", NOW);
    private final TeamId teamId = initialization.team().id();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final RepositoryBindingId bindingId = RepositoryBindingId.generate();

    private final Store store = new Store(initialization);
    private final FakeBindingRepository bindings = new FakeBindingRepository();
    private final FakeKnowledgeRepository knowledge = new FakeKnowledgeRepository();
    private final FakeEmbeddingExecutor embeddings = new FakeEmbeddingExecutor();
    private final FakeKnowledgeVectorStore knowledgeVectors = new FakeKnowledgeVectorStore();
    private final FakeChunkVectorStore chunkVectors = new FakeChunkVectorStore();
    private final FakeGenerationCatalog generations = new FakeGenerationCatalog();

    @BeforeEach
    void seedActiveBinding() {
        bindings.values.put(bindingId, binding(RepositoryBindingStatus.ACTIVE, organizationId));
        generations.snapshot = Optional.of(new GenerationSnapshot(
                new RepositoryGenerationKey(indexKey(), 7L),
                AuditMetadata.createdBy(actor.id(), NOW)));
    }

    // ------------------------------------------------------------------ guards

    @Test
    void guardsDenyForeignOrganizationStrangersAndNonMembers() {
        Principal stranger =
                Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(OrganizationId.generate()),
                        PrincipalType.USER,
                        Optional.empty(),
                        "Stranger",
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        NOW);

        assertThrows(PolicyDeniedException.class, () -> service(true).retrieve(
                new TeamAccessContext(stranger, true), organizationId, teamId,
                knowledgeOnly("onboarding")));
        store.members = List.of();
        assertThrows(PolicyDeniedException.class, () -> service(true).retrieve(
                access(), organizationId, teamId, knowledgeOnly("onboarding")));
    }

    @Test
    void aClosedSwitchDegradesToEmptyCandidatesWithoutEmbedding() {
        KnowledgeRetrievalResult result = service(false).retrieve(
                access(), organizationId, teamId, knowledgeOnly("how do we deploy"));

        assertTrue(result.candidates().isEmpty());
        assertEquals(List.of(DegradationReasonCode.RETRIEVAL_DISABLED), result.degradations());
        assertTrue(embeddings.commands.isEmpty(), "a skipped search spends no embedding");
        assertTrue(knowledgeVectors.queries.isEmpty());
    }

    @Test
    void anAbsentVectorStoreDegradesToRetrievalDisabled() {
        KnowledgeRetrievalService vectorless = new KnowledgeRetrievalService(
                embeddings, generations, knowledgeVectors, null,
                knowledge, bindings, store, store, ChunkingPolicy.defaults(), true);

        KnowledgeRetrievalResult result = vectorless.retrieve(
                access(), organizationId, teamId, knowledgeOnly("how do we deploy"));

        assertTrue(result.candidates().isEmpty());
        assertEquals(List.of(DegradationReasonCode.RETRIEVAL_DISABLED), result.degradations());
        assertTrue(embeddings.commands.isEmpty());
    }

    @Test
    void anEmbeddingFailureDegradesTheWholeSearch() {
        embeddings.deliveryFailure =
                new EmbeddingDeliveryException(ModelConnectionHealthFailureCode.PROVIDER_REJECTED);

        KnowledgeRetrievalResult result = service(true).retrieve(
                access(), organizationId, teamId, bothSources(8));

        assertTrue(result.candidates().isEmpty());
        assertEquals(List.of(DegradationReasonCode.EMBEDDING_PROVIDER_UNAVAILABLE),
                result.degradations());
        assertEquals(1, embeddings.commands.size(), "the attempt happened once and degraded");
        assertTrue(knowledgeVectors.queries.isEmpty());
        assertTrue(chunkVectors.queries.isEmpty());
    }

    // ------------------------------------------------------------------ repository route

    @Test
    void aRepositoryTargetWithoutAnActiveGenerationDegradesOnlyThatSource() {
        generations.snapshot = Optional.empty();
        knowledgeVectors.hits = List.of(hit(entrySeed(1, 1, "Onboarding", "Read first"), 0.9));

        KnowledgeRetrievalResult result = service(true).retrieve(
                access(), organizationId, teamId, bothSources(8));

        assertEquals(1, result.candidates().size(), "the knowledge route still answers");
        assertEquals(ManifestSourceType.KNOWLEDGE_ENTRY, result.candidates().get(0).source());
        assertEquals(List.of(DegradationReasonCode.NO_MATCHING_GENERATION),
                result.degradations());
        assertTrue(chunkVectors.queries.isEmpty(),
                "no generation means no chunk search, never a fallback branch");
    }

    @Test
    void repositoryRouteValidatesTheBindingFourCoordinates() {
        // Unknown binding: the aggregate name must surface.
        KnowledgeRetrievalQuery unknown = new KnowledgeRetrievalQuery(
                "deploy", Set.of(ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(
                        projectId, RepositoryBindingId.generate(), COMMIT),
                8);
        AggregateNotFoundException missing = assertThrows(
                AggregateNotFoundException.class,
                () -> service(true).retrieve(access(), organizationId, teamId, unknown));
        assertTrue(missing.getMessage().contains("RepositoryBinding"),
                "the aggregate name must surface: " + missing.getMessage());

        // Foreign Organization: the same not-found shape, never a cross-Team search.
        RepositoryBindingId foreignId = RepositoryBindingId.generate();
        bindings.values.put(foreignId,
                binding(RepositoryBindingStatus.ACTIVE, OrganizationId.generate()));
        KnowledgeRetrievalQuery foreign = new KnowledgeRetrievalQuery(
                "deploy", Set.of(ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, foreignId, COMMIT),
                8);
        assertThrows(AggregateNotFoundException.class,
                () -> service(true).retrieve(access(), organizationId, teamId, foreign));

        // Disabled binding: the enqueue-time validation shape.
        RepositoryBindingId disabledId = RepositoryBindingId.generate();
        bindings.values.put(disabledId,
                binding(RepositoryBindingStatus.DISABLED, organizationId));
        KnowledgeRetrievalQuery disabled = new KnowledgeRetrievalQuery(
                "deploy", Set.of(ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, disabledId, COMMIT),
                8);
        DomainValidationException rejected = assertThrows(
                DomainValidationException.class,
                () -> service(true).retrieve(access(), organizationId, teamId, disabled));
        assertTrue(rejected.getMessage().contains("repositoryIndex.bindingId"),
                "the enqueue-time field name must surface: " + rejected.getMessage());
    }

    @Test
    void theRepositoryRoutePinsTheFullIndexKeyCoordinateAndTheEmbeddingCommand() {
        chunkVectors.hits = List.of(chunk(1, "docs/deploy.md", 11, 20, 0.9));
        KnowledgeRetrievalQuery query = bothSources(8);

        KnowledgeRetrievalResult result = service(true).retrieve(
                access(), organizationId, teamId, query);

        assertEquals(indexKey(), generations.keys.get(0),
                "resolveModel + the injected policy hash + the target make the full coordinate");
        assertEquals(7L, chunkVectors.queries.get(0).generation().buildSequence());
        assertEquals(1, embeddings.commands.size());
        assertEquals(List.of(query.query()), embeddings.commands.get(0).inputs());
        assertEquals(actor.id(), embeddings.commands.get(0).actor());
        assertEquals(organizationId, embeddings.commands.get(0).organizationId());
        assertEquals(teamId, embeddings.commands.get(0).teamId());
        RetrievalCandidate.RepositoryFragment fragment =
                result.candidates().get(result.candidates().size() - 1).fragments().get(0);
        assertEquals(bindingId, fragment.bindingId());
        assertEquals(COMMIT, fragment.commit());
        assertEquals(7L, fragment.generationBuildSequence());
    }

    // ------------------------------------------------------------------ ordering, merging, budget

    @Test
    void ordersByScoreThenKnowledgeFirstOnTiesThenNewerRevision() {
        knowledgeVectors.hits = List.of(
                hit(entrySeed(1, 2, "Older", "second"), 0.90),
                hit(entrySeed(2, 5, "Newest", "first"), 0.90),
                hit(entrySeed(3, 1, "Lowest", "third"), 0.80));
        chunkVectors.hits = List.of(
                chunk(1, "docs/strong.md", 11, 20, 0.95),
                chunk(1, "docs/tied.md", 21, 30, 0.90),
                chunk(1, "docs/weak.md", 41, 50, 0.85));

        KnowledgeRetrievalResult result = service(true).retrieve(
                access(), organizationId, teamId, bothSources(8));

        List<Object> identity = result.candidates().stream()
                .map(candidate -> candidate.source() == ManifestSourceType.KNOWLEDGE_ENTRY
                        ? candidate.entry().entryId()
                        : candidate.fragments().get(0).path())
                .toList();
        // 0.95 chunk, then the 0.90 tie: knowledge first with the newer revision ahead,
        // the chunk behind, then 0.85 and 0.80 on raw score.
        assertEquals(List.of(
                "docs/strong.md",
                slots.get(2), slots.get(1),
                "docs/tied.md",
                "docs/weak.md",
                slots.get(3)), identity);
        assertEquals(List.of(1, 2, 3, 4, 5, 6),
                result.candidates().stream().map(RetrievalCandidate::rank).toList());
        assertTrue(result.degradations().isEmpty());
    }

    @Test
    void mergesAdjacentSameFileFragmentsIntoOneCandidateKeepingEverySpan() {
        chunkVectors.hits = List.of(
                chunk(1, "docs/run.md", 11, 20, 0.90),
                chunk(2, "docs/run.md", 21, 30, 0.70),   // touching the previous span
                chunk(3, "docs/run.md", 51, 60, 0.80),   // a gap: its own candidate
                chunk(1, "docs/overlap.md", 40, 45, 0.60),
                chunk(2, "docs/overlap.md", 45, 50, 0.50));  // overlapping: merged

        KnowledgeRetrievalResult result = service(true).retrieve(
                access(), organizationId, teamId, repositoryOnly(8));

        List<List<Integer>> spans = result.candidates().stream()
                .map(candidate -> candidate.fragments().stream()
                        .map(fragment -> fragment.startLine() * 100 + fragment.endLine())
                        .toList())
                .toList();
        assertEquals(List.of(
                List.of(1120, 2130),
                List.of(5160),
                List.of(4045, 4550)), spans);
        assertEquals(List.of(0.90, 0.80, 0.60),
                result.candidates().stream().map(RetrievalCandidate::score).toList(),
                "a merged group keeps its highest span score for ordering");
    }

    @Test
    void topKTruncatesAfterMergingAndBoundsEachVectorSearch() {
        knowledgeVectors.hits = List.of(
                hit(entrySeed(1, 1, "One", "one"), 0.9),
                hit(entrySeed(2, 1, "Two", "two"), 0.7),
                hit(entrySeed(3, 1, "Three", "three"), 0.5));
        chunkVectors.hits = List.of(
                chunk(1, "docs/a.md", 1, 10, 0.95),
                chunk(2, "docs/a.md", 11, 20, 0.94),    // merges with the span above
                chunk(1, "docs/b.md", 1, 10, 0.6));

        KnowledgeRetrievalResult result = service(true).retrieve(
                access(), organizationId, teamId, bothSources(2));

        assertEquals(2, result.candidates().size(), "the ceiling applies after merging");
        assertEquals(List.of(1, 2),
                result.candidates().stream().map(RetrievalCandidate::rank).toList());
        assertEquals("docs/a.md", result.candidates().get(0).fragments().get(0).path());
        assertEquals(List.of(1, 10, 11, 20),
                result.candidates().get(0).fragments().stream()
                        .flatMap(fragment -> Stream.of(
                                fragment.startLine(), fragment.endLine()))
                        .toList());
        assertEquals(ManifestSourceType.KNOWLEDGE_ENTRY, result.candidates().get(1).source());
        assertEquals(slots.get(1), result.candidates().get(1).entry().entryId());
        assertEquals(2, knowledgeVectors.queries.get(0).topK());
        assertEquals(2, chunkVectors.queries.get(0).topK());
    }

    @Test
    void aVersionVanishedBetweenTheGateAndTheReadIsSkipped() {
        // The nearest hit names revision 2, but the effective head moved on and the
        // version lookup misses: the hit disappears without inventing a degradation.
        knowledgeVectors.hits = List.of(hit(entrySeed(1, 2, "Vanished", "gone"), 0.9));
        entrySeed(1, 1, "Vanished", "gone");

        KnowledgeRetrievalResult result = service(true).retrieve(
                access(), organizationId, teamId, knowledgeOnly("anything"));

        assertTrue(result.candidates().isEmpty());
        assertTrue(result.degradations().isEmpty(),
                "a genuinely empty search is not a degradation");
    }

    // ------------------------------------------------------------------ fixtures

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private KnowledgeRetrievalService service(boolean retrievalEnabled) {
        return new KnowledgeRetrievalService(
                embeddings, generations, knowledgeVectors, chunkVectors,
                knowledge, bindings, store, store, ChunkingPolicy.defaults(),
                retrievalEnabled);
    }

    private KnowledgeRetrievalQuery knowledgeOnly(String text) {
        return new KnowledgeRetrievalQuery(
                text, Set.of(ManifestSourceType.KNOWLEDGE_ENTRY), null,
                KnowledgeRetrievalQuery.DEFAULT_TOP_K);
    }

    private KnowledgeRetrievalQuery bothSources(int topK) {
        return new KnowledgeRetrievalQuery(
                "how do we deploy",
                Set.of(ManifestSourceType.KNOWLEDGE_ENTRY, ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, bindingId, COMMIT),
                topK);
    }

    private KnowledgeRetrievalQuery repositoryOnly(int topK) {
        return new KnowledgeRetrievalQuery(
                "how do we deploy", Set.of(ManifestSourceType.REPOSITORY_CHUNK),
                new KnowledgeRetrievalQuery.RepositoryTarget(projectId, bindingId, COMMIT),
                topK);
    }

    private RepositoryIndexKey indexKey() {
        return new RepositoryIndexKey(
                organizationId, teamId, bindingId, COMMIT,
                ChunkingPolicy.defaults().policyHash(), MODEL);
    }

    /** Entry seeds register the id so order assertions can name entries stably. */
    private KnowledgeEntryId entrySeed(int slot, long revision, String title, String body) {
        KnowledgeEntryId id = slots.computeIfAbsent(slot, ignored -> KnowledgeEntryId.generate());
        knowledge.versions.put(id, KnowledgeEntryVersion.create(
                id,
                new TeamScope(organizationId, teamId),
                new KnowledgeEntryRevision(revision),
                revision == 1
                        ? Optional.empty()
                        : Optional.of(new KnowledgeEntryRevision(revision - 1)),
                title, body, actor.id(), NOW));
        return id;
    }

    private final Map<Integer, KnowledgeEntryId> slots = new LinkedHashMap<>();

    private ScoredKnowledgeEmbedding hit(KnowledgeEntryId entryId, double score) {
        KnowledgeEntryVersion version = knowledge.versions.get(entryId);
        return new ScoredKnowledgeEmbedding(
                entryId, version.revision(), version.contentHash().value(), score);
    }

    private ScoredRepositoryChunk chunk(
            int chunkSeq, String path, int startLine, int endLine, double score) {
        return new ScoredRepositoryChunk(
                chunkSeq, path, "markdown", startLine, endLine,
                "a".repeat(64), "span " + chunkSeq + " of " + path, score);
    }

    private RepositoryBinding binding(RepositoryBindingStatus status, OrganizationId owner) {
        return RepositoryBinding.reconstitute(
                bindingId,
                new RepositoryBindingScope(owner, teamId, WorkspaceId.generate(), projectId),
                RepositoryKind.LOCAL_MANAGED,
                new RepositoryKey("repo-" + bindingId.value()),
                new RepositoryBranchName("main"),
                status,
                0,
                AuditMetadata.createdBy(actor.id(), NOW));
    }

    /** The guard collaborator pair, trimmed to this slice (no roles: reads are member-level). */
    private static final class Store implements TeamRepository, TeamMembershipQuery {
        private final TeamInitialization initialization;
        private List<TeamMember> members;

        private Store(TeamInitialization initialization) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
        }

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(organizationId)
                            && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(OrganizationId organizationId, TeamId id) {
            return Optional.empty();
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId organization, TeamId team) {
            return members;
        }
    }

    private static final class FakeBindingRepository implements RepositoryBindingRepository {
        private final Map<RepositoryBindingId, RepositoryBinding> values = new LinkedHashMap<>();

        @Override
        public RepositoryBinding create(RepositoryBinding binding) {
            values.put(binding.id(), binding);
            return binding;
        }

        @Override
        public RepositoryBinding update(RepositoryBinding binding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RepositoryBinding> findById(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                RepositoryBindingId bindingId) {
            return Optional.ofNullable(values.get(bindingId))
                    .filter(binding -> binding.scope().organizationId().equals(organizationId)
                            && binding.scope().teamId().equals(teamId)
                            && binding.scope().workProjectId().equals(workProjectId));
        }

        @Override
        public Optional<RepositoryBinding> findByKey(
                OrganizationId organizationId,
                TeamId teamId,
                WorkProjectId workProjectId,
                RepositoryKey repositoryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RepositoryBinding> findByWorkProject(
                OrganizationId organizationId, TeamId teamId, WorkProjectId workProjectId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Only the version read is exercised; every other read fails the test loudly. */
    private static final class FakeKnowledgeRepository implements KnowledgeRepository {
        private final Map<KnowledgeEntryId, KnowledgeEntryVersion> versions =
                new LinkedHashMap<>();

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
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntry> findByKey(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryKey entryKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeEntryPage findByTeam(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryFilter filter,
                KnowledgeEntryPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findVersion(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            KnowledgeEntryVersion version = versions.get(entryId);
            return version != null && version.revision().equals(revision)
                    ? Optional.of(version) : Optional.empty();
        }

        @Override
        public KnowledgeEntryVersionPage findVersionHistory(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeVersionPageRequest pageRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findEffectiveVersion(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId organizationId, TeamId teamId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Governance seam: a fixed model always resolves, delivery is configurable. */
    private static final class FakeEmbeddingExecutor implements KnowledgeEmbeddingExecutor {
        private final List<TeamEmbeddingCommand> commands = new ArrayList<>();
        private EmbeddingDeliveryException deliveryFailure;

        @Override
        public EmbeddingModelRevision resolveModel(OrganizationId organizationId, TeamId teamId) {
            return MODEL;
        }

        @Override
        public EmbeddingBatchResult embed(TeamEmbeddingCommand command) {
            commands.add(command);
            if (deliveryFailure != null) {
                throw deliveryFailure;
            }
            float[][] vectors = new float[command.inputs().size()][MODEL.dimension()];
            for (float[] vector : vectors) {
                Arrays.fill(vector, 0.25f);
            }
            return new EmbeddingBatchResult(
                    List.of(vectors),
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

    private static final class FakeKnowledgeVectorStore implements KnowledgeEmbeddingVectorStore {
        private final List<KnowledgeEmbeddingQuery> queries = new ArrayList<>();
        private List<ScoredKnowledgeEmbedding> hits = List.of();

        @Override
        public void replace(KnowledgeEmbeddingVector vector) {
            throw new UnsupportedOperationException("retrieval never writes");
        }

        @Override
        public int deleteByEntry(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            throw new UnsupportedOperationException("retrieval never writes");
        }

        @Override
        public boolean isEmbedded(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScoredKnowledgeEmbedding> nearest(KnowledgeEmbeddingQuery query) {
            queries.add(query);
            return hits;
        }
    }

    private static final class FakeChunkVectorStore implements RepositoryChunkVectorStore {
        private final List<RepositoryChunkEmbeddingQuery> queries = new ArrayList<>();
        private List<ScoredRepositoryChunk> hits = List.of();

        @Override
        public void replaceBatch(
                RepositoryGenerationKey generation, List<RepositoryChunkVector> vectors) {
            throw new UnsupportedOperationException("retrieval never writes");
        }

        @Override
        public int deleteByGeneration(RepositoryGenerationKey generation) {
            throw new UnsupportedOperationException("retrieval never writes");
        }

        @Override
        public List<ScoredRepositoryChunk> nearest(RepositoryChunkEmbeddingQuery query) {
            queries.add(query);
            return hits;
        }
    }

    private static final class FakeGenerationCatalog implements GenerationCatalog {
        private final List<RepositoryIndexKey> keys = new ArrayList<>();
        private Optional<GenerationSnapshot> snapshot = Optional.empty();

        @Override
        public Optional<GenerationSnapshot> findActiveGeneration(RepositoryIndexKey indexKey) {
            keys.add(indexKey);
            return snapshot;
        }
    }
}
