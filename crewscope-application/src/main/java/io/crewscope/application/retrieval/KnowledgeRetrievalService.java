package io.crewscope.application.retrieval;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.EmbeddingBatchResult;
import io.crewscope.application.embedding.EmbeddingDeliveryException;
import io.crewscope.application.embedding.TeamEmbeddingCommand;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.EmbeddingModelRevision;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.RepositoryGenerationKey;
import io.crewscope.domain.retrieval.RepositoryIndexKey;
import io.crewscope.domain.retrieval.chunking.ChunkingPolicy;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified retrieval over the two explicit knowledge sources (M10-A01, S01 §3.4): Team
 * knowledge entries through the effective-version gate and repository chunks through
 * exactly one ACTIVE generation, both narrowed by tenant predicates inside the vector
 * SQL before any top-K. The explainable order is score descending, ties within
 * {@value #SCORE_TIE_TOLERANCE} prefer knowledge entries over repository chunks, then
 * the newer version or build; adjacent same-file chunk hits merge into one candidate
 * that keeps every fragment span. Degradations are explicit — a skipped source never
 * masquerades as "searched and found nothing". Retrieval is read-only: every query is
 * embedded through the governed chain (EMBEDDING usage facts included), and an absent
 * vector store or a closed {@code crewscope.knowledge.retrieval.enabled} switch answers
 * {@link DegradationReasonCode#RETRIEVAL_DISABLED} rather than failing.
 */
public final class KnowledgeRetrievalService {

    /** S01 §3.4: equal scores within this tolerance tie-break by source, not by chance. */
    private static final double SCORE_TIE_TOLERANCE = 1e-9;

    private final KnowledgeEmbeddingExecutor embeddings;
    private final GenerationCatalog generations;
    private final KnowledgeEmbeddingVectorStore knowledgeVectors;
    private final RepositoryChunkVectorStore chunkVectors;
    private final KnowledgeRepository knowledge;
    private final RepositoryBindingRepository bindings;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final ChunkingPolicy policy;
    private final boolean retrievalEnabled;

    public KnowledgeRetrievalService(
            KnowledgeEmbeddingExecutor embeddings,
            GenerationCatalog generations,
            KnowledgeEmbeddingVectorStore knowledgeVectors,
            RepositoryChunkVectorStore chunkVectors,
            KnowledgeRepository knowledge,
            RepositoryBindingRepository bindings,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            ChunkingPolicy policy,
            boolean retrievalEnabled) {
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.generations = Objects.requireNonNull(generations, "generations");
        // The two vector stores are deliberately nullable: on a vector-less deployment
        // the retrieval switch cannot honor any query and answers RETRIEVAL_DISABLED.
        this.knowledgeVectors = knowledgeVectors;
        this.chunkVectors = chunkVectors;
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.retrievalEnabled = retrievalEnabled;
    }

    /**
     * Runs one unified retrieval. Reads guard at Team-member level (the KnowledgeCommandService
     * guard shape); a repository target is validated against the four-coordinate binding
     * lookup before anything is searched, so one Team never reads another Team's mirror.
     */
    public KnowledgeRetrievalResult retrieve(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeRetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        Principal actor = requireOrganizationUser(context, organizationId);
        Team team = requireTeam(organizationId, teamId);
        TeamMember member = requireActiveMember(actor, team);

        if (!retrievalEnabled || knowledgeVectors == null || chunkVectors == null) {
            return new KnowledgeRetrievalResult(
                    List.of(), List.of(DegradationReasonCode.RETRIEVAL_DISABLED));
        }

        EmbeddingModelRevision model;
        float[] queryVector;
        try {
            EmbeddingBatchResult embedded = embeddings.embed(new TeamEmbeddingCommand(
                    organizationId, teamId, member.userPrincipalId(),
                    UUID.randomUUID(), UUID.randomUUID(), List.of(query.query())));
            model = embedded.model();
            queryVector = embedded.vectors().get(0);
        } catch (EmbeddingDeliveryException | DomainValidationException failure) {
            // A governed model that cannot resolve or deliver right now degrades the whole
            // search; it must never surface as 500 nor as an empty-but-"successful" page.
            return new KnowledgeRetrievalResult(
                    List.of(), List.of(DegradationReasonCode.EMBEDDING_PROVIDER_UNAVAILABLE));
        }

        List<DegradationReasonCode> degradations = new ArrayList<>();
        List<Scored> scored = new ArrayList<>();
        if (query.sources().contains(ManifestSourceType.KNOWLEDGE_ENTRY)) {
            for (ScoredKnowledgeEmbedding hit : knowledgeVectors.nearest(
                    new KnowledgeEmbeddingQuery(
                            organizationId, teamId, model, queryVector, query.topK()))) {
                // The effective gate already ran inside the SQL; a version that vanished
                // between the gate and this lookup was retired mid-flight and is skipped.
                knowledge.findVersion(organizationId, teamId, hit.entryId(), hit.revision())
                        .ifPresent(version -> scored.add(Scored.knowledge(
                                hit.score(), version.revision().value(), version)));
            }
        }
        if (query.sources().contains(ManifestSourceType.REPOSITORY_CHUNK)) {
            KnowledgeRetrievalQuery.RepositoryTarget target = query.repository();
            requireBinding(organizationId, teamId, target);
            RepositoryIndexKey indexKey = new RepositoryIndexKey(
                    organizationId, teamId, target.bindingId(), target.commit(),
                    policy.policyHash(), model);
            Optional<GenerationSnapshot> snapshot = generations.findActiveGeneration(indexKey);
            if (snapshot.isEmpty()) {
                degradations.add(DegradationReasonCode.NO_MATCHING_GENERATION);
            } else {
                RepositoryGenerationKey generation = snapshot.orElseThrow().generationKey();
                for (Scored group : mergeAdjacentFragments(chunkVectors.nearest(
                        new RepositoryChunkEmbeddingQuery(
                                generation, queryVector, query.topK())), generation)) {
                    scored.add(group);
                }
            }
        }

        scored.sort(READABLE_ORDER);
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (Scored hit : scored) {
            if (candidates.size() >= query.topK()) {
                break;
            }
            candidates.add(hit.toCandidate(candidates.size() + 1));
        }
        return new KnowledgeRetrievalResult(candidates, degradations);
    }

    // ------------------------------------------------------------------ ordering and merging

    /** S01 §3.4 frozen order: score desc, knowledge before chunks on ties, newer first. */
    private static final Comparator<Scored> READABLE_ORDER = (left, right) -> {
        if (Math.abs(left.score - right.score) > SCORE_TIE_TOLERANCE) {
            return Double.compare(right.score, left.score);
        }
        int sourceRankLeft = left.source == ManifestSourceType.KNOWLEDGE_ENTRY ? 0 : 1;
        int sourceRankRight = right.source == ManifestSourceType.KNOWLEDGE_ENTRY ? 0 : 1;
        if (sourceRankLeft != sourceRankRight) {
            return Integer.compare(sourceRankLeft, sourceRankRight);
        }
        return Long.compare(right.recency, left.recency);
    };

    /**
     * Merges same-file hits whose spans touch or overlap into one candidate per run
     * (S01 §3.4). Each merged group keeps its highest score for ordering; the candidate
     * count can only shrink, never inflate.
     */
    private static List<Scored> mergeAdjacentFragments(
            List<ScoredRepositoryChunk> hits, RepositoryGenerationKey generation) {
        Map<String, List<ScoredRepositoryChunk>> byPath = new LinkedHashMap<>();
        for (ScoredRepositoryChunk hit : hits) {
            byPath.computeIfAbsent(hit.path(), path -> new ArrayList<>()).add(hit);
        }
        List<Scored> merged = new ArrayList<>();
        for (List<ScoredRepositoryChunk> spans : byPath.values()) {
            spans.sort(Comparator.comparingInt(ScoredRepositoryChunk::startLine));
            List<ScoredRepositoryChunk> run = new ArrayList<>();
            int runEnd = -1;
            for (ScoredRepositoryChunk span : spans) {
                if (!run.isEmpty() && span.startLine() > runEnd + 1) {
                    merged.add(Scored.chunks(run, generation));
                    run = new ArrayList<>();
                }
                run.add(span);
                runEnd = Math.max(runEnd, span.endLine());
            }
            if (!run.isEmpty()) {
                merged.add(Scored.chunks(run, generation));
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------ guards

    private RepositoryBinding requireBinding(
            OrganizationId organizationId, TeamId teamId,
            KnowledgeRetrievalQuery.RepositoryTarget target) {
        RepositoryBinding binding = bindings
                .findById(organizationId, teamId, target.projectId(), target.bindingId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "RepositoryBinding", target.bindingId()));
        if (!binding.acceptsNewTargets()) {
            throw new DomainValidationException(
                    "repositoryIndex.bindingId",
                    "must reference an active RepositoryBinding of this Team's WorkProject");
        }
        return binding;
    }

    /** The KnowledgeCommandService guard shape: reads at member level. */
    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teams.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException("team.initializationStatus", "must be READY");
        }
        Team team = teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        if (!team.isActive()) {
            throw new DomainValidationException("team.status", "must be ACTIVE");
        }
        return team;
    }

    private TeamMember requireActiveMember(Principal actor, Team team) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's knowledge"));
    }

    private static Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        Principal actor = Objects.requireNonNull(context, "context").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    // ------------------------------------------------------------------ internal carrier

    /**
     * Pre-candidate carrier: the ordering keys (score, source, recency = revision or
     * build sequence) plus exactly one payload variant per source.
     */
    private static final class Scored {
        private final double score;
        private final ManifestSourceType source;
        private final long recency;
        private final KnowledgeEntryVersion version;
        private final RepositoryGenerationKey generation;
        private final List<ScoredRepositoryChunk> group;

        private Scored(
                double score,
                ManifestSourceType source,
                long recency,
                KnowledgeEntryVersion version,
                RepositoryGenerationKey generation,
                List<ScoredRepositoryChunk> group) {
            this.score = score;
            this.source = source;
            this.recency = recency;
            this.version = version;
            this.generation = generation;
            this.group = group;
        }

        static Scored knowledge(
                double score, long revision, KnowledgeEntryVersion version) {
            return new Scored(
                    score, ManifestSourceType.KNOWLEDGE_ENTRY, revision, version, null, null);
        }

        static Scored chunks(
                List<ScoredRepositoryChunk> group, RepositoryGenerationKey generation) {
            double best = group.stream().mapToDouble(ScoredRepositoryChunk::score).max()
                    .orElseThrow();
            return new Scored(
                    best, ManifestSourceType.REPOSITORY_CHUNK,
                    generation.buildSequence(), null, generation, List.copyOf(group));
        }

        RetrievalCandidate toCandidate(int rank) {
            if (source == ManifestSourceType.KNOWLEDGE_ENTRY) {
                return new RetrievalCandidate(
                        source, rank, score,
                        new RetrievalCandidate.KnowledgeEntryHit(
                                version.entryId(), version.revision(), version.title(),
                                version.contentHash().value(), version.content()),
                        List.of());
            }
            List<RetrievalCandidate.RepositoryFragment> fragments = new ArrayList<>();
            for (ScoredRepositoryChunk span : group) {
                fragments.add(new RetrievalCandidate.RepositoryFragment(
                        generation.indexKey().repositoryBindingId(),
                        generation.indexKey().sourceCommit(),
                        generation.buildSequence(),
                        span.chunkSeq(), span.path(), span.language(),
                        span.startLine(), span.endLine(), span.contentHash(), span.content()));
            }
            return new RetrievalCandidate(source, rank, score, null, fragments);
        }
    }
}
