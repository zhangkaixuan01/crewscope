package io.crewscope.application.retrieval;

import io.crewscope.application.embedding.EmbeddingClient;
import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.InjectionBudgetPlanner;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.retrieval.TokenEstimator;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Prompt assembly for the coding execution chain (M10-I02b, S01 §3.4/§3.7): retrieval
 * candidates and assistant memory become one budgeted injection per (execution,
 * attempt). The first attempt seals the manifest before any model call — an append
 * conflict converges onto the stored manifest — and later rounds of the same attempt
 * reuse it: fresh candidates are filtered down to exactly what the sealed manifest
 * injected, so the evidence never outgrows what was sealed. Authorization runs
 * entirely under the task creator: retrieval is fail-closed there and memory belongs
 * to the same member. The deployment switches are constructive — injection off means
 * no assembly, no manifest and no prompt block; a disabled retrieval switch degrades
 * through the manifest instead of masquerading as an empty result; a disabled memory
 * switch skips the memory layer entirely, including its TTL renewal.
 */
public final class PromptInjectionService {

    private final KnowledgeRetrievalService retrieval;
    private final AgentMemoryService memory;
    private final InjectionManifestRepository manifests;
    private final boolean injectionEnabled;
    private final boolean memoryEnabled;
    private final InjectionBudgetPlanner.InjectionBudgetLimits limits;
    private final ManifestSourceRef skillInstruction;
    private final TimeProvider timeProvider;

    public PromptInjectionService(
            KnowledgeRetrievalService retrieval,
            AgentMemoryService memory,
            InjectionManifestRepository manifests,
            boolean injectionEnabled,
            boolean memoryEnabled,
            InjectionBudgetPlanner.InjectionBudgetLimits limits,
            ManifestSourceRef skillInstruction,
            TimeProvider timeProvider) {
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.injectionEnabled = injectionEnabled;
        this.memoryEnabled = memoryEnabled;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.skillInstruction = Objects.requireNonNull(skillInstruction, "skillInstruction");
        if (skillInstruction.type() != ManifestSourceType.SKILL_INSTRUCTION
                || !skillInstruction.injected()) {
            throw new DomainValidationException(
                    "promptInjectionService.skillInstruction",
                    "must be an INJECTED SKILL_INSTRUCTION reference");
        }
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /**
     * Assembles one injection. Fail-closed upstream: authorization and business-budget
     * failures propagate and nothing is sealed; degradation codes from retrieval pass
     * into the manifest transparently.
     */
    public PromptInjectionPlan assemble(PromptInjectionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!injectionEnabled) {
            return PromptInjectionPlan.disabled();
        }
        return assembleEnabled(request);
    }

    /**
     * Whether prompt injection is switched on for this deployment. The worker gateway
     * asks before it even resolves the injection actor: a disabled switch means zero
     * behavior change, and an unavailable task creator must not fail a round that
     * injection is not part of.
     */
    public boolean enabled() {
        return injectionEnabled;
    }

    private PromptInjectionPlan assembleEnabled(PromptInjectionRequest request) {
        Optional<InjectionManifest> stored = manifests.findByAttempt(
                request.organizationId(), request.teamId(),
                request.executionId(), request.attempt());

        KnowledgeRetrievalResult result = retrieval.retrieve(
                new TeamAccessContext(request.injectionActor(), false),
                request.organizationId(), request.teamId(), retrievalQuery(request));
        List<RetrievalCandidate> knowledge =
                candidates(result, ManifestSourceType.KNOWLEDGE_ENTRY);
        List<RetrievalCandidate> chunks = candidates(result, ManifestSourceType.REPOSITORY_CHUNK);
        List<AgentMemoryEntry> entries = memoryEnabled
                ? memory.list(ownerKey(request))
                : List.of();

        InjectionManifest manifest;
        if (stored.isPresent()) {
            manifest = stored.orElseThrow();
        } else {
            InjectionBudgetPlanner.InjectionBudgetPlan budget =
                    withConfiguredTotal(InjectionBudgetPlanner.plan(
                            effectiveLimits(), layerCosts(knowledge, chunks, entries)));
            int keepKnowledge = budget.keptCounts().get(ManifestSourceType.KNOWLEDGE_ENTRY);
            int keepChunks = budget.keptCounts().get(ManifestSourceType.REPOSITORY_CHUNK);
            int keepMemory = budget.keptCounts().get(ManifestSourceType.MEMORY_PREFERENCE);
            manifest = seal(request, references(
                    request, knowledge, chunks, entries,
                    keepKnowledge, keepChunks, keepMemory),
                    budget, result.degradations());
            knowledge = prefix(knowledge, keepKnowledge);
            chunks = prefix(chunks, keepChunks);
            entries = prefix(entries, keepMemory);
        }
        // Converge onto whatever the final manifest sealed as injected. On a fresh
        // seal this is a no-op by construction; on a converged append conflict or a
        // later round of the same attempt it drops every fresh candidate the sealed
        // evidence does not cover — the injection never outgrows what was sealed.
        Set<RefKey> injected = injectedKeys(manifest);
        knowledge = knowledge.stream()
                .filter(candidate -> injected.contains(knowledgeKey(candidate)))
                .toList();
        chunks = chunks.stream()
                .filter(candidate -> candidate.fragments().stream()
                        .allMatch(fragment -> injected.contains(chunkKey(fragment))))
                .toList();
        entries = entries.stream()
                .filter(entry -> injected.contains(memoryKey(entry)))
                .toList();
        renew(ownerKey(request), entries);
        return new PromptInjectionPlan(manifest, knowledge, chunks, entries, true);
    }

    // ------------------------------------------------------------------ query

    /**
     * The retrieval query text: objective and acceptance criteria composed the way the
     * instruction composes them, degrading to objective-only when the composition
     * overruns the embedding input bound (the objective alone is always within it).
     */
    private static KnowledgeRetrievalQuery retrievalQuery(PromptInjectionRequest request) {
        String composite = request.acceptanceCriteria().isEmpty()
                ? request.objective()
                : request.objective() + "\n" + String.join("\n", request.acceptanceCriteria());
        String query = composite.length() <= EmbeddingClient.MAX_INPUT_CHARS
                ? composite
                : request.objective();
        return new KnowledgeRetrievalQuery(
                query,
                Set.of(ManifestSourceType.KNOWLEDGE_ENTRY, ManifestSourceType.REPOSITORY_CHUNK),
                request.repositoryTarget(),
                KnowledgeRetrievalQuery.DEFAULT_TOP_K);
    }

    private static List<RetrievalCandidate> candidates(
            KnowledgeRetrievalResult result, ManifestSourceType source) {
        return result.candidates().stream()
                .filter(candidate -> candidate.source() == source)
                .toList();
    }

    // ------------------------------------------------------------------ sealing

    private InjectionManifest seal(
            PromptInjectionRequest request,
            List<ManifestSourceRef> references,
            InjectionBudgetPlanner.InjectionBudgetPlan budget,
            List<DegradationReasonCode> degradations) {
        InjectionManifest candidate = new InjectionManifest(
                InjectionManifestId.generate(),
                request.executionId(),
                request.attempt(),
                references,
                budget.trims(),
                budget.budget(),
                degradations,
                timeProvider.now());
        try {
            return manifests.append(candidate);
        } catch (InjectionManifestConflictException raced) {
            return manifests.findByAttempt(
                            request.organizationId(), request.teamId(),
                            request.executionId(), request.attempt())
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * The reference set of one assembly: the hard-retained skill instruction first —
     * then the request's already-proven dynamic Team Skills, also hard-retained (A03b:
     * their load evidence is the very reason they may load, so the budget never trims
     * them) — then each layer's kept candidates as INJECTED followed by the trimmed
     * tail as CANDIDATE — trimmed repository candidates contribute one reference per
     * fragment.
     */
    private List<ManifestSourceRef> references(
            PromptInjectionRequest request,
            List<RetrievalCandidate> knowledge,
            List<RetrievalCandidate> chunks,
            List<AgentMemoryEntry> entries,
            int keepKnowledge,
            int keepChunks,
            int keepMemory) {
        List<ManifestSourceRef> references = new ArrayList<>();
        references.add(skillInstruction);
        references.addAll(request.dynamicSkillInstructions());
        for (int index = 0; index < knowledge.size(); index++) {
            references.add(knowledgeRef(knowledge.get(index), index < keepKnowledge));
        }
        for (int index = 0; index < chunks.size(); index++) {
            boolean injected = index < keepChunks;
            for (RetrievalCandidate.RepositoryFragment fragment : chunks.get(index).fragments()) {
                references.add(chunkRef(fragment, injected));
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            references.add(memoryRef(entries.get(index), index < keepMemory));
        }
        return references;
    }

    private static ManifestSourceRef knowledgeRef(
            RetrievalCandidate candidate, boolean injected) {
        RetrievalCandidate.KnowledgeEntryHit hit = candidate.entry();
        return new ManifestSourceRef(
                ManifestSourceType.KNOWLEDGE_ENTRY,
                hit.entryId().value().toString(),
                hit.revision().value(),
                hit.contentHash(),
                stage(injected));
    }

    private static ManifestSourceRef chunkRef(
            RetrievalCandidate.RepositoryFragment fragment, boolean injected) {
        return new ManifestSourceRef(
                ManifestSourceType.REPOSITORY_CHUNK,
                fragment.path() + "#" + fragment.startLine() + "-" + fragment.endLine(),
                fragment.generationBuildSequence(),
                fragment.contentHash(),
                stage(injected));
    }

    private static ManifestSourceRef memoryRef(AgentMemoryEntry entry, boolean injected) {
        // The policy version (never the entry version, which starts at 0) keeps the
        // reference inside the manifest's positive-version contract; the value hash is
        // computed here because memory entries carry no content hash of their own.
        return new ManifestSourceRef(
                ManifestSourceType.MEMORY_PREFERENCE,
                entry.memoryKey().value(),
                entry.policy().version(),
                sha256Hex(entry.value()),
                stage(injected));
    }

    private static ManifestSourceStage stage(boolean injected) {
        return injected ? ManifestSourceStage.INJECTED : ManifestSourceStage.CANDIDATE;
    }

    // ------------------------------------------------------------------ budget inputs

    /**
     * One rendered coordinate header ({@code [ ... hash ... ]}) per candidate or
     * fragment: an ASCII header under 192 characters tops out at 64 tokens under the
     * frozen formula's /3 arm, while the longest real header is ~165 characters.
     */
    private static final long RENDERED_ENTRY_HEADER_TOKENS = 64;

    /** Section tags plus the untrusted preamble of the whole block (~300 ASCII chars). */
    private static final long RENDER_FIXED_OVERHEAD_TOKENS = 100;

    /**
     * Layer budgets unchanged; the total absorbs the fixed rendering overhead (section
     * tags and the preamble). Pricing the rendered text — escaped, with headers — makes
     * the block provably fit the instruction bound: rendered characters stay at or below
     * 3 x effective budget, so the default 8,192 budget yields <= ~24.6k rendered
     * characters inside the 30,000 instruction bound regardless of candidate count.
     */
    private InjectionBudgetPlanner.InjectionBudgetLimits effectiveLimits() {
        return new InjectionBudgetPlanner.InjectionBudgetLimits(
                Math.max(0, limits.totalTokens() - RENDER_FIXED_OVERHEAD_TOKENS),
                limits.knowledgeTokens(),
                limits.chunkTokens(),
                limits.memoryTokens());
    }

    /**
     * The snapshot reports the configured total — the fixed rendering overhead only
     * narrows what the planner may keep, it does not rewrite the configuration.
     */
    private InjectionBudgetPlanner.InjectionBudgetPlan withConfiguredTotal(
            InjectionBudgetPlanner.InjectionBudgetPlan budget) {
        return new InjectionBudgetPlanner.InjectionBudgetPlan(
                budget.keptCounts(),
                budget.trims(),
                new PromptBudgetSnapshot(
                        limits.totalTokens(),
                        budget.budget().knowledgeTokens(),
                        budget.budget().chunkTokens(),
                        budget.budget().memoryTokens()));
    }

    private static EnumMap<ManifestSourceType, List<Long>> layerCosts(
            List<RetrievalCandidate> knowledge,
            List<RetrievalCandidate> chunks,
            List<AgentMemoryEntry> entries) {
        EnumMap<ManifestSourceType, List<Long>> costs = new EnumMap<>(ManifestSourceType.class);
        costs.put(ManifestSourceType.KNOWLEDGE_ENTRY, knowledge.stream()
                .map(candidate -> TokenEstimator.estimateAll(List.of(
                        InjectionTextEscaper.escape(candidate.entry().title()),
                        InjectionTextEscaper.escape(candidate.entry().content())))
                        + RENDERED_ENTRY_HEADER_TOKENS)
                .toList());
        costs.put(ManifestSourceType.REPOSITORY_CHUNK, chunks.stream()
                .map(candidate -> candidate.fragments().stream()
                        .map(fragment -> TokenEstimator.estimateAll(List.of(
                                InjectionTextEscaper.escape(fragment.path()),
                                InjectionTextEscaper.escape(fragment.content())))
                                + RENDERED_ENTRY_HEADER_TOKENS)
                        .mapToLong(Long::longValue)
                        .sum())
                .toList());
        costs.put(ManifestSourceType.MEMORY_PREFERENCE, entries.stream()
                .map(entry -> TokenEstimator.estimate(
                        InjectionTextEscaper.escape(entry.value()))
                        + RENDERED_ENTRY_HEADER_TOKENS)
                .toList());
        return costs;
    }

    // ------------------------------------------------------------------ convergence

    /** The quadruple the sealed INJECTED references are reconciled against. */
    private record RefKey(
            ManifestSourceType type, String sourceId, long version, String contentHash) {
    }

    private static RefKey key(ManifestSourceRef reference) {
        return new RefKey(reference.type(), reference.sourceId(),
                reference.version(), reference.contentHash());
    }

    private static Set<RefKey> injectedKeys(InjectionManifest manifest) {
        Set<RefKey> keys = new LinkedHashSet<>();
        for (ManifestSourceRef reference : manifest.references()) {
            if (reference.injected()) {
                keys.add(key(reference));
            }
        }
        return keys;
    }

    private static RefKey knowledgeKey(RetrievalCandidate candidate) {
        return key(knowledgeRef(candidate, true));
    }

    private static RefKey chunkKey(RetrievalCandidate.RepositoryFragment fragment) {
        return key(chunkRef(fragment, true));
    }

    private static RefKey memoryKey(AgentMemoryEntry entry) {
        return key(memoryRef(entry, true));
    }

    // ------------------------------------------------------------------ memory renewal

    /**
     * Injection is use: every memory entry that actually reached the prompt slides its
     * TTL. Renewal is best effort — a false return or a failed renewal never fails the
     * model call.
     */
    private void renew(AgentMemoryOwnerKey owner, List<AgentMemoryEntry> injected) {
        if (!memoryEnabled) {
            return;
        }
        for (AgentMemoryEntry entry : injected) {
            try {
                memory.touch(owner, entry.memoryKey());
            } catch (RuntimeException renewalFailure) {
                // best effort by contract; the injection is already sealed
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static AgentMemoryOwnerKey ownerKey(PromptInjectionRequest request) {
        return new AgentMemoryOwnerKey(
                request.organizationId(),
                request.teamId(),
                request.agentProfileId(),
                request.injectionActor().id());
    }

    private static <T> List<T> prefix(List<T> values, int keep) {
        return List.copyOf(values.subList(0, keep));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 digest unavailable", unavailable);
        }
    }
}
