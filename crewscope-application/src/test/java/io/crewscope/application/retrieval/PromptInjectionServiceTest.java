package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.application.memory.AgentMemoryService;
import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.agent.AgentMemoryKey;
import io.crewscope.domain.coding.RepositoryBindingId;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.retrieval.DegradationReasonCode;
import io.crewscope.domain.retrieval.InjectionBudgetPlanner;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.domain.retrieval.TokenEstimator;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Assembly contract of the prompt injection service (M10-I02b): the three-switch matrix,
 * the two-phase budget landing in the manifest, (execution, attempt) idempotency with
 * conflict convergence and sealed-set filtering of fresh candidates, memory TTL renewal
 * only for injected entries, fail-closed retrieval, and the reference/query derivation
 * rules — against mocked retrieval/memory collaborators and an in-memory manifest store.
 */
final class PromptInjectionServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final UtcTimestamp EXPIRES =
            UtcTimestamp.from(NOW.value().plus(Duration.ofDays(90)));
    private static final SourceCommit COMMIT =
            new SourceCommit("0123456789012345678901234567890123456789");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final TaskExecutionId executionId = TaskExecutionId.generate();
    private final AgentProfileId profileId = AgentProfileId.generate();
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Creator",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
    private final AgentMemoryOwnerKey ownerKey =
            new AgentMemoryOwnerKey(organizationId, teamId, profileId, actor.id());

    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final AgentMemoryService memory = mock(AgentMemoryService.class);
    private final FakeManifestRepository manifests = new FakeManifestRepository();

    private static final ManifestSourceRef SKILL_REF = new ManifestSourceRef(
            ManifestSourceType.SKILL_INSTRUCTION, "java-spring-v1_crewscope-java-spring-v1",
            1, hex(0xA5F4), ManifestSourceStage.INJECTED);

    private static final InjectionBudgetPlanner.InjectionBudgetLimits GENEROUS =
            new InjectionBudgetPlanner.InjectionBudgetLimits(8192, 3072, 4096, 1024);

    // ------------------------------------------------------------------ switches

    @Test
    void injectionSwitchOffAssemblesNothingAtAll() {
        PromptInjectionPlan plan = service(false, true, GENEROUS).assemble(request());

        assertFalse(plan.injected());
        assertNull(plan.manifest());
        assertTrue(plan.knowledge().isEmpty() && plan.chunks().isEmpty()
                && plan.memory().isEmpty());
        assertEquals(0, manifests.appends, "no manifest is sealed while switched off");
        verifyNoInteractions(retrieval, memory);
    }

    @Test
    void retrievalDegradationsPassThroughIntoTheSealedManifest() {
        stubRetrieval(new KnowledgeRetrievalResult(
                List.of(), List.of(DegradationReasonCode.RETRIEVAL_DISABLED)));
        when(memory.list(any())).thenReturn(List.of());

        PromptInjectionPlan plan = service(true, true, GENEROUS).assemble(request());

        assertTrue(plan.injected());
        assertEquals(List.of(DegradationReasonCode.RETRIEVAL_DISABLED),
                plan.manifest().degradations());
        assertEquals(List.of(SKILL_REF), plan.manifest().references(),
                "a degraded search still seals a manifest, carrying only the skill reference");
        assertTrue(plan.knowledge().isEmpty() && plan.chunks().isEmpty());
    }

    @Test
    void memorySwitchOffSkipsTheMemoryLayerCompletely() {
        stubRetrieval(new KnowledgeRetrievalResult(
                List.of(knowledge(1, KnowledgeEntryId.generate(), 3, "Title", "Content")), List.of()));

        PromptInjectionPlan plan = service(true, false, GENEROUS).assemble(request());

        verify(memory, never()).list(any());
        verify(memory, never()).touch(any(), any());
        assertTrue(plan.memory().isEmpty());
        assertTrue(plan.manifest().references().stream()
                .noneMatch(reference -> reference.type() == ManifestSourceType.MEMORY_PREFERENCE),
                "no memory reference may exist while memory is switched off");
    }

    // ------------------------------------------------------------------ budget

    @Test
    void budgetTrimsLandInTheManifestAsStagesTrimsAndSnapshot() {
        RetrievalCandidate bigKnowledge =
                knowledge(2, KnowledgeEntryId.generate(), 3, "Big title", "Big content body");
        RetrievalCandidate trimmedChunk =
                chunk(3, "src/Big.java", 1, 80, "package io.example; class Big {}");
        AgentMemoryEntry keptPreferenceOne = entry("reply-language", "简体中文", 1);
        AgentMemoryEntry keptPreferenceTwo = entry("code-style", "follow the repo", 1);
        AgentMemoryEntry trimmedPreference = entry("stack", "java spring postgres", 1);
        // Layer budgets price the rendered text (escaped, plus 64 tokens per rendered
        // coordinate header — see PromptInjectionService.RENDERED_ENTRY_HEADER_TOKENS):
        // knowledge 100 keeps the first candidate (70) and trims the second (73);
        // chunks 8 trims the whole layer (79 per fragment); memory 140 keeps two
        // (67 + 70) and trims the third (71); the total leaves room for the fixed
        // rendering overhead so phase two stays out of the picture.
        InjectionBudgetPlanner.InjectionBudgetLimits limits =
                new InjectionBudgetPlanner.InjectionBudgetLimits(320, 100, 8, 140);
        RetrievalCandidate keptKnowledge =
                knowledge(1, KnowledgeEntryId.generate(), 3, "Title", "Content body");
        stubRetrieval(new KnowledgeRetrievalResult(
                List.of(keptKnowledge, bigKnowledge, trimmedChunk), List.of()));
        when(memory.list(any()))
                .thenReturn(List.of(keptPreferenceOne, keptPreferenceTwo, trimmedPreference));

        PromptInjectionPlan plan = service(true, true, limits).assemble(request());

        assertEquals(List.of(keptKnowledge), plan.knowledge());
        assertTrue(plan.chunks().isEmpty(), "the oversized chunk layer is fully trimmed");
        assertEquals(List.of(keptPreferenceOne, keptPreferenceTwo), plan.memory());

        List<ManifestSourceRef> references = plan.manifest().references();
        assertEquals(ManifestSourceType.SKILL_INSTRUCTION, references.get(0).type());
        assertTrue(references.get(1).injected(), "kept knowledge is INJECTED");
        assertFalse(references.get(2).injected(), "the knowledge tail is CANDIDATE");
        assertFalse(references.get(3).injected(), "the trimmed chunk fragments are CANDIDATE");
        assertFalse(references.get(references.size() - 1).injected(),
                "the memory tail is CANDIDATE");
        assertEquals(3, plan.manifest().references().stream()
                .filter(reference -> !reference.injected()).count());
        assertEquals(3, plan.manifest().trims().size(),
                "one trim record per trimmed layer");
        assertEquals(new PromptBudgetSnapshot(
                320,
                TokenEstimator.estimateAll(List.of(
                        InjectionTextEscaper.escape("Title"),
                        InjectionTextEscaper.escape("Content body"))) + 64,
                0,
                TokenEstimator.estimate(InjectionTextEscaper.escape("简体中文")) + 64
                        + TokenEstimator.estimate(
                                InjectionTextEscaper.escape("follow the repo")) + 64),
                plan.manifest().budget());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void appendConflictConvergesOntoTheStoredWinner() {
        RetrievalCandidate sealed = knowledge(1, KnowledgeEntryId.generate(), 3, "Title", "Content");
        RetrievalCandidate extra = knowledge(2, KnowledgeEntryId.generate(), 3, "Extra", "Extra body");
        InjectionManifest winner = new InjectionManifest(
                InjectionManifestId.generate(), executionId, 1,
                List.of(SKILL_REF, knowledgeRef(sealed, true)), List.of(),
                new PromptBudgetSnapshot(8192, 10, 0, 0), List.of(), NOW);
        manifests.raceWinner = winner;
        stubRetrieval(new KnowledgeRetrievalResult(List.of(sealed, extra), List.of()));
        when(memory.list(any())).thenReturn(List.of());

        PromptInjectionPlan plan = service(true, true, GENEROUS).assemble(request());

        assertEquals(winner, plan.manifest(), "the loser converges onto the stored manifest");
        assertEquals(List.of(sealed), plan.knowledge(),
                "fresh candidates beyond the sealed injection set are not injected");
        assertEquals(1, manifests.appends);
    }

    @Test
    void laterRoundsOfTheSameAttemptReuseTheSealedManifest() {
        KnowledgeEntryId entryId = KnowledgeEntryId.generate();
        RetrievalCandidate sealed = knowledge(1, entryId, 3, "Title", "Content");
        AgentMemoryEntry sealedPreference = entry("reply-language", "简体中文", 1);
        stubRetrieval(new KnowledgeRetrievalResult(List.of(sealed), List.of()));
        when(memory.list(any())).thenReturn(List.of(sealedPreference));
        PromptInjectionService service = service(true, true, GENEROUS);

        InjectionManifest first = service.assemble(request()).manifest();

        RetrievalCandidate extraKnowledge =
                knowledge(2, KnowledgeEntryId.generate(), 1, "New", "New body");
        RetrievalCandidate extraChunk = chunk(3, "src/New.java", 1, 40, "class New {}");
        AgentMemoryEntry extraPreference = entry("stack", "java spring", 1);
        stubRetrieval(new KnowledgeRetrievalResult(
                List.of(sealed, extraKnowledge, extraChunk), List.of()));
        when(memory.list(any())).thenReturn(List.of(sealedPreference, extraPreference));

        PromptInjectionPlan second = service.assemble(request());

        assertEquals(first, second.manifest(), "round two re-seals nothing");
        assertEquals(1, manifests.appends);
        assertEquals(List.of(sealed), second.knowledge());
        assertTrue(second.chunks().isEmpty());
        assertEquals(List.of(sealedPreference), second.memory());
        // Both rounds injected the sealed preference, so both rounds renewed its TTL.
        verify(memory, times(2)).touch(ownerKey, sealedPreference.memoryKey());
        verify(memory, never()).touch(ownerKey, extraPreference.memoryKey());
    }

    @Test
    void aNewAttemptSealsItsOwnManifest() {
        stubRetrieval(new KnowledgeRetrievalResult(
                List.of(knowledge(1, KnowledgeEntryId.generate(), 3, "Title", "Content")),
                List.of()));
        when(memory.list(any())).thenReturn(List.of());
        PromptInjectionService service = service(true, true, GENEROUS);

        service.assemble(request());
        service.assemble(new PromptInjectionRequest(
                organizationId, teamId, WorkspaceId.generate(), projectId,
                executionId, 2, profileId, actor, "Implement the endpoint",
                List.of("tests pass"), request().repositoryTarget()));

        assertEquals(2, manifests.appends, "each attempt seals exactly one manifest");
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    void retrievalAuthorizationFailuresPropagateAndNothingIsSealed() {
        // Consecutive throws: re-stubbing an already-throwing stub would fire the old
        // answer inside the second when() call itself.
        when(retrieval.retrieve(any(), any(), any(), any()))
                .thenThrow(new PolicyDeniedException("access team knowledge"))
                .thenThrow(new AggregateNotFoundException("Team", teamId));

        assertThrows(PolicyDeniedException.class,
                () -> service(true, true, GENEROUS).assemble(request()));
        assertThrows(AggregateNotFoundException.class,
                () -> service(true, true, GENEROUS).assemble(request()));
        assertEquals(0, manifests.appends, "a failed assembly must not leave a manifest behind");
    }

    // ------------------------------------------------------------------ derivation

    @Test
    void referencesCarryTheFrozenProvenanceOfEveryLayer() throws Exception {
        KnowledgeEntryId entryId = KnowledgeEntryId.generate();
        RetrievalCandidate hit = knowledge(1, entryId, 3, "Title", "Content body");
        RetrievalCandidate merged = chunk(2, "src/Main.java", 10, 90, "class A {}", "class B {}");
        AgentMemoryEntry preference = entry("reply-language", "简体中文", 4);
        stubRetrieval(new KnowledgeRetrievalResult(List.of(hit, merged), List.of()));
        when(memory.list(any())).thenReturn(List.of(preference));

        InjectionManifest manifest = service(true, true, GENEROUS).assemble(request()).manifest();

        List<ManifestSourceRef> references = manifest.references();
        assertEquals(SKILL_REF, references.get(0));
        assertEquals(new ManifestSourceRef(ManifestSourceType.KNOWLEDGE_ENTRY,
                entryId.value().toString(), 3, hit.entry().contentHash(),
                ManifestSourceStage.INJECTED), references.get(1));
        assertEquals(new ManifestSourceRef(ManifestSourceType.REPOSITORY_CHUNK,
                "src/Main.java#10-90", 7, merged.fragments().get(0).contentHash(),
                ManifestSourceStage.INJECTED), references.get(2));
        assertEquals(new ManifestSourceRef(ManifestSourceType.REPOSITORY_CHUNK,
                "src/Main.java#10-90", 7, merged.fragments().get(1).contentHash(),
                ManifestSourceStage.INJECTED), references.get(3));
        assertEquals(new ManifestSourceRef(ManifestSourceType.MEMORY_PREFERENCE,
                "reply-language", 4, sha256("简体中文"), ManifestSourceStage.INJECTED),
                references.get(4),
                "the memory reference carries the policy version, never the entry version");
    }

    @Test
    void queryComposesObjectiveAndCriteriaAndDegradesToObjectiveOnlyWhenTooLong() {
        stubRetrieval(new KnowledgeRetrievalResult(List.of(), List.of()));
        when(memory.list(any())).thenReturn(List.of());
        PromptInjectionRequest request = request();
        service(true, true, GENEROUS).assemble(request);

        ArgumentCaptor<KnowledgeRetrievalQuery> query =
                ArgumentCaptor.forClass(KnowledgeRetrievalQuery.class);
        verify(retrieval).retrieve(any(), any(), any(), query.capture());
        assertEquals("Implement the endpoint\ntests pass\nno regressions",
                query.getValue().query());
        assertEquals(8, query.getValue().topK());
        assertEquals(request.repositoryTarget(), query.getValue().repository());

        String longObjective = "o".repeat(32_000);
        PromptInjectionRequest longRequest = new PromptInjectionRequest(
                organizationId, teamId, WorkspaceId.generate(), projectId,
                executionId, 2, profileId, actor, longObjective,
                List.of("c".repeat(5_000)), request.repositoryTarget());
        service(true, true, GENEROUS).assemble(longRequest);

        ArgumentCaptor<KnowledgeRetrievalQuery> degraded =
                ArgumentCaptor.forClass(KnowledgeRetrievalQuery.class);
        verify(retrieval, times(2))
                .retrieve(any(), any(), any(), degraded.capture());
        assertEquals(longObjective, degraded.getValue().query(),
                "an over-bound composition degrades to the objective alone");
    }

    // ------------------------------------------------------------------ dynamic skills (A03b)

    @Test
    void dynamicTeamSkillReferencesSealAsInjectedLoadEvidenceNextToTheBuiltinSkill() {
        stubRetrieval(new KnowledgeRetrievalResult(List.of(), List.of()));
        when(memory.list(any())).thenReturn(List.of());
        ManifestSourceRef dynamicOne = new ManifestSourceRef(
                ManifestSourceType.SKILL_INSTRUCTION, "code-review", 1,
                hex(0xBEEF), ManifestSourceStage.INJECTED);
        ManifestSourceRef dynamicTwo = new ManifestSourceRef(
                ManifestSourceType.SKILL_INSTRUCTION, "deploy-runbook", 4,
                hex(0xC0DE), ManifestSourceStage.INJECTED);
        PromptInjectionRequest withSkills = new PromptInjectionRequest(
                organizationId, teamId, WorkspaceId.generate(), projectId,
                executionId, 1, profileId, actor, "Implement the endpoint",
                List.of(), request().repositoryTarget(), List.of(dynamicOne, dynamicTwo));

        PromptInjectionPlan plan = service(true, true, GENEROUS).assemble(withSkills);

        assertEquals(List.of(SKILL_REF, dynamicOne, dynamicTwo),
                plan.manifest().references(),
                "resolved dynamic skills seal as hard-retained evidence after the built-in");
        assertEquals(1, manifests.appends);

        // A later round of the same attempt reuses the sealed manifest verbatim.
        PromptInjectionPlan replay = service(true, true, GENEROUS).assemble(withSkills);
        assertEquals(plan.manifest(), replay.manifest());
        assertEquals(1, manifests.appends);
    }

    @Test
    void dynamicSkillInstructionsMustArriveAsInjectedSkillReferences() {
        assertThrows(DomainValidationException.class, () -> new PromptInjectionRequest(
                organizationId, teamId, WorkspaceId.generate(), projectId,
                executionId, 1, profileId, actor, "Implement the endpoint",
                List.of(), request().repositoryTarget(), List.of(new ManifestSourceRef(
                        ManifestSourceType.KNOWLEDGE_ENTRY, "code-review", 1,
                        hex(0xBEEF), ManifestSourceStage.INJECTED))));
        assertThrows(DomainValidationException.class, () -> new PromptInjectionRequest(
                organizationId, teamId, WorkspaceId.generate(), projectId,
                executionId, 1, profileId, actor, "Implement the endpoint",
                List.of(), request().repositoryTarget(), List.of(new ManifestSourceRef(
                        ManifestSourceType.SKILL_INSTRUCTION, "code-review", 1,
                        hex(0xBEEF), ManifestSourceStage.CANDIDATE))));
    }

    // ------------------------------------------------------------------ fixtures

    private PromptInjectionService service(
            boolean injectionEnabled,
            boolean memoryEnabled,
            InjectionBudgetPlanner.InjectionBudgetLimits limits) {
        return new PromptInjectionService(
                retrieval, memory, manifests, injectionEnabled, memoryEnabled,
                limits, SKILL_REF, fixedClock());
    }

    private void stubRetrieval(KnowledgeRetrievalResult result) {
        when(retrieval.retrieve(any(), any(), any(), any())).thenReturn(result);
    }

    private PromptInjectionRequest request() {
        return new PromptInjectionRequest(
                organizationId, teamId, WorkspaceId.generate(), projectId,
                executionId, 1, profileId, actor, "Implement the endpoint",
                List.of("tests pass", "no regressions"),
                new KnowledgeRetrievalQuery.RepositoryTarget(
                        projectId, RepositoryBindingId.generate(), COMMIT));
    }

    private static RetrievalCandidate knowledge(
            int rank, KnowledgeEntryId entryId, long revision, String title, String content) {
        return new RetrievalCandidate(
                ManifestSourceType.KNOWLEDGE_ENTRY, rank, 0.9,
                new RetrievalCandidate.KnowledgeEntryHit(
                        entryId, new KnowledgeEntryRevision(revision), title,
                        hex(0x1000 + rank), content),
                List.of());
    }

    private static RetrievalCandidate chunk(
            int rank, String path, int startLine, int endLine, String... contents) {
        List<RetrievalCandidate.RepositoryFragment> fragments = new ArrayList<>();
        for (int index = 0; index < contents.length; index++) {
            fragments.add(new RetrievalCandidate.RepositoryFragment(
                    RepositoryBindingId.generate(), COMMIT, 7, index + 1, path, "java",
                    startLine, endLine, hex(0x2000 + rank * 10 + index), contents[index]));
        }
        return new RetrievalCandidate(
                ManifestSourceType.REPOSITORY_CHUNK, rank, 0.8, null, List.copyOf(fragments));
    }

    private AgentMemoryEntry entry(String key, String value, long policyVersion) {
        return AgentMemoryEntry.write(
                ownerKey,
                new AgentMemoryPolicyReference(AgentMemoryPolicy.DEFAULT_POLICY_ID, policyVersion),
                new AgentMemoryKey(key), value, 0, EXPIRES, actor.id(), NOW);
    }

    private static ManifestSourceRef knowledgeRef(
            RetrievalCandidate candidate, boolean injected) {
        return new ManifestSourceRef(
                ManifestSourceType.KNOWLEDGE_ENTRY,
                candidate.entry().entryId().value().toString(),
                candidate.entry().revision().value(),
                candidate.entry().contentHash(),
                injected ? ManifestSourceStage.INJECTED : ManifestSourceStage.CANDIDATE);
    }

    private static TimeProvider fixedClock() {
        return () -> NOW;
    }

    private static String hex(long seed) {
        return (Long.toHexString(seed) + "0".repeat(64)).substring(0, 64);
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** In-memory (execution, attempt) store with a single-shot race knob. */
    private static final class FakeManifestRepository implements InjectionManifestRepository {

        private final Map<TaskExecutionId, InjectionManifest> values = new LinkedHashMap<>();
        private int appends;

        /** When set, the next append stores this winner first and throws the conflict. */
        private InjectionManifest raceWinner;

        @Override
        public InjectionManifest append(InjectionManifest manifest) {
            appends++;
            if (raceWinner != null) {
                InjectionManifest winner = raceWinner;
                raceWinner = null;
                values.put(winner.executionId(), winner);
                throw new InjectionManifestConflictException(
                        manifest.executionId(), manifest.attempt());
            }
            InjectionManifest existing = values.get(manifest.executionId());
            if (existing != null && existing.attempt() == manifest.attempt()) {
                throw new InjectionManifestConflictException(
                        manifest.executionId(), manifest.attempt());
            }
            values.put(manifest.executionId(), manifest);
            return manifest;
        }

        @Override
        public Optional<InjectionManifest> findByAttempt(
                OrganizationId organizationId,
                TeamId teamId,
                TaskExecutionId executionId,
                int attempt) {
            return Optional.ofNullable(values.get(executionId))
                    .filter(manifest -> manifest.attempt() == attempt);
        }

        @Override
        public List<InjectionManifest> findByExecution(
                OrganizationId organizationId,
                TeamId teamId,
                TaskExecutionId executionId) {
            return Optional.ofNullable(values.get(executionId)).stream().toList();
        }
    }
}
