package io.crewscope.application.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.retrieval.InjectionManifest;
import io.crewscope.domain.retrieval.InjectionManifestId;
import io.crewscope.domain.retrieval.ManifestSourceKey;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.PromptBudgetSnapshot;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M10-I02c service contract: visibility rides the execution-detail face, the
 * ownership check runs before any payload evaluation (foreign tenants see the same
 * 404), feedback anchors on the INJECTED union across attempts, claimed receipts
 * reconcile against one attempt's sealed manifest and never exceed it, and both
 * write sides converge structurally instead of duplicating rows.
 */
class InjectionReferenceServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final TaskId TASK_ID = TaskId.generate();
    private static final TaskExecutionId EXECUTION_ID = TaskExecutionId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final TaskExecutionRepository executionRepository =
            mock(TaskExecutionRepository.class);
    private final FakeManifestStore manifests = new FakeManifestStore();
    private final FakeReferenceStore references = new FakeReferenceStore();
    private final TeamAccessContext context = new TeamAccessContext(actor(), false);

    private InjectionReferenceService service() {
        return new InjectionReferenceService(
                accessPolicy, taskRepository, executionRepository, manifests, references,
                directTransactions(), () -> NOW);
    }

    @BeforeEach
    void wireOwnership() {
        Task task = mock(Task.class);
        TaskExecution execution = mock(TaskExecution.class);
        WorkItemScope scope = scope();
        when(task.id()).thenReturn(TASK_ID);
        when(task.scope()).thenReturn(scope);
        when(execution.id()).thenReturn(EXECUTION_ID);
        when(execution.taskId()).thenReturn(TASK_ID);
        when(execution.scope()).thenReturn(scope);
        when(taskRepository.findById(ORGANIZATION_ID, TASK_ID))
                .thenReturn(Optional.of(task));
        when(executionRepository.findById(ORGANIZATION_ID, EXECUTION_ID))
                .thenReturn(Optional.of(execution));
    }

    // ------------------------------------------------------------------ visibility

    @Test
    void aMemberWhoCannotSeeTheTeamIsDeniedBeforeAnyLookup() {
        doThrow(new PolicyDeniedException("see this team")).when(accessPolicy)
                .requireVisibleTeam(context, ORGANIZATION_ID, TEAM_ID);

        assertThrows(PolicyDeniedException.class, () -> service().view(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID));
        assertThrows(PolicyDeniedException.class, () -> service().submitFeedback(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, knowledgeKey()));
    }

    @Test
    void aMissingTaskFailsClosedWithTheUniformNotFound() {
        when(taskRepository.findById(ORGANIZATION_ID, TASK_ID)).thenReturn(Optional.empty());

        AggregateNotFoundException exception = assertThrows(
                AggregateNotFoundException.class,
                () -> service().view(context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID));
        assertEquals("Task", exception.error().details().get("aggregateType"));
    }

    @Test
    void anExecutionFromAnotherTaskOrTenantFailsClosedWithTheSameNotFound() {
        TaskExecution foreign = mock(TaskExecution.class);
        when(foreign.id()).thenReturn(EXECUTION_ID);
        when(foreign.taskId()).thenReturn(TaskId.generate());
        when(executionRepository.findById(ORGANIZATION_ID, EXECUTION_ID))
                .thenReturn(Optional.of(foreign));

        AggregateNotFoundException exception = assertThrows(
                AggregateNotFoundException.class,
                () -> service().view(context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID));
        assertEquals("TaskExecution", exception.error().details().get("aggregateType"));
    }

    // ------------------------------------------------------------------ view

    @Test
    void viewAssemblesManifestsTheCallersOwnFeedbackAndReceipts() {
        manifests.store(manifest(1,
                ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3, HASH_A,
                        ManifestSourceStage.INJECTED)));
        manifests.store(manifest(2,
                ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3, HASH_A,
                        ManifestSourceStage.INJECTED),
                ref(ManifestSourceType.MEMORY_PREFERENCE, "theme", 1, HASH_B,
                        ManifestSourceStage.CANDIDATE)));
        references.storeFeedback(new InjectionReferenceFeedback(
                EXECUTION_ID, knowledgeKey(), ACTOR,
                InjectionFeedbackKind.NOT_APPLICABLE, NOW));
        references.storeFeedback(new InjectionReferenceFeedback(
                EXECUTION_ID, knowledgeKey(), PrincipalId.generate(),
                InjectionFeedbackKind.NOT_APPLICABLE, NOW));
        references.storeClaimed(new InjectionClaimedReferences(
                EXECUTION_ID, 2, List.of(knowledgeKey()), NOW));

        InjectionReferenceService.InjectionReferenceView view = service().view(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID);

        assertEquals(List.of(1, 2), view.manifests().stream()
                .map(InjectionManifest::attempt).toList());
        // Only the caller's own feedback row leaves the service — one, not two.
        assertEquals(1, view.memberFeedback().size());
        assertEquals(ACTOR, view.memberFeedback().get(0).memberPrincipalId());
        assertEquals(1, view.claimed().size());
        assertEquals(2, view.claimed().get(0).attempt());
    }

    @Test
    void viewWithoutAnySealedManifestStaysEmpty() {
        InjectionReferenceService.InjectionReferenceView view = service().view(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID);

        assertEquals(List.of(), view.manifests());
        assertEquals(List.of(), view.memberFeedback());
        assertEquals(List.of(), view.claimed());
    }

    // ------------------------------------------------------------------ feedback

    @Test
    void feedbackOnAnInjectedKeyRecordsUnderTheActingMember() {
        manifests.store(manifest(1, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3,
                HASH_A, ManifestSourceStage.INJECTED)));

        InjectionReferenceFeedback recorded = service().submitFeedback(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, knowledgeKey());

        assertEquals(ACTOR, recorded.memberPrincipalId());
        assertEquals(InjectionFeedbackKind.NOT_APPLICABLE, recorded.kind());
        assertEquals(NOW, recorded.createdAt());
    }

    @Test
    void feedbackOnABudgetCutCandidateIsRefused() {
        manifests.store(manifest(1, ref(ManifestSourceType.MEMORY_PREFERENCE, "theme", 1,
                HASH_B, ManifestSourceStage.CANDIDATE)));

        assertThrows(FeedbackReferenceOutsideManifestException.class, () -> service()
                .submitFeedback(context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID,
                        new ManifestSourceKey(ManifestSourceType.MEMORY_PREFERENCE,
                                "theme", 1, HASH_B)));
    }

    @Test
    void feedbackAnchorsOnTheInjectedUnionAcrossAttempts() {
        // entry-2 only ever surfaced as INJECTED in attempt 2's manifest.
        manifests.store(manifest(1, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3,
                HASH_A, ManifestSourceStage.INJECTED)));
        manifests.store(manifest(2, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-2", 1,
                HASH_B, ManifestSourceStage.INJECTED)));

        InjectionReferenceFeedback recorded = service().submitFeedback(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID,
                new ManifestSourceKey(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-2", 1, HASH_B));

        assertEquals("entry-2", recorded.source().sourceId());
    }

    @Test
    void feedbackReplaysConvergeOnTheStoredRow() {
        manifests.store(manifest(1, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3,
                HASH_A, ManifestSourceStage.INJECTED)));
        UtcTimestamp firstAt = UtcTimestamp.parse("2026-10-04T08:00:00Z");
        references.storeFeedback(new InjectionReferenceFeedback(
                EXECUTION_ID, knowledgeKey(), ACTOR,
                InjectionFeedbackKind.NOT_APPLICABLE, firstAt));

        InjectionReferenceFeedback replay = service().submitFeedback(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, knowledgeKey());

        assertEquals(firstAt, replay.createdAt());
        assertEquals(1, references.feedbackRows.size());
    }

    // ------------------------------------------------------------------ claimed

    @Test
    void claimedWithinTheAttemptManifestFilesTheReceipt() {
        manifests.store(manifest(1, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3,
                HASH_A, ManifestSourceStage.INJECTED)));

        InjectionClaimedReferences filed = service().submitClaimed(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, 1,
                List.of(knowledgeKey()));

        assertEquals(1, filed.attempt());
        assertEquals(List.of(knowledgeKey()), filed.claimed());
    }

    @Test
    void claimedBeyondTheManifestIsRefusedWithTheOutsideList() {
        manifests.store(manifest(1, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3,
                HASH_A, ManifestSourceStage.INJECTED)));
        ManifestSourceKey outside = new ManifestSourceKey(
                ManifestSourceType.REPOSITORY_CHUNK, "src/Main.java#1-2", 5, HASH_B);

        ClaimedReferenceOutsideManifestException exception = assertThrows(
                ClaimedReferenceOutsideManifestException.class,
                () -> service().submitClaimed(context, ORGANIZATION_ID, TEAM_ID, TASK_ID,
                        EXECUTION_ID, 1, List.of(knowledgeKey(), outside)));

        assertEquals(List.of(outside), exception.outside());
    }

    @Test
    void claimedWithoutASealedManifestForThatAttemptIsRefused() {
        manifests.store(manifest(1, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3,
                HASH_A, ManifestSourceStage.INJECTED)));

        assertThrows(InjectionManifestNotSealedException.class, () -> service()
                .submitClaimed(context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, 2,
                        List.of(knowledgeKey())));
    }

    @Test
    void anIdenticalClaimedSetReplaysWhileADifferentSetConflicts() {
        manifests.store(manifest(1, ref(ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3,
                HASH_A, ManifestSourceStage.INJECTED)));
        InjectionClaimedReferences stored = new InjectionClaimedReferences(
                EXECUTION_ID, 1, List.of(knowledgeKey()),
                UtcTimestamp.parse("2026-10-04T08:00:00Z"));
        references.storeClaimed(stored);

        InjectionClaimedReferences replay = service().submitClaimed(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, 1,
                // A shuffled input is still the same receipt set.
                List.of(knowledgeKey()));
        assertEquals(stored.createdAt(), replay.createdAt());

        assertThrows(InjectionClaimedReferenceConflictException.class, () -> service()
                .submitClaimed(context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, 1,
                        List.of()));
    }

    @Test
    void anEmptyClaimedSetAndTheSkillInstructionAreBothClaimable() {
        ManifestSourceRef skill = ref(ManifestSourceType.SKILL_INSTRUCTION,
                "java-spring-v1_crewscope-java-spring-v1", 1, HASH_B,
                ManifestSourceStage.INJECTED);
        manifests.store(manifest(1, skill));
        manifests.store(manifest(2, skill));

        // Receipts are unique per (execution, attempt): two attempts, two verdicts.
        InjectionClaimedReferences skillReceipt = service().submitClaimed(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, 1,
                List.of(new ManifestSourceKey(ManifestSourceType.SKILL_INSTRUCTION,
                        "java-spring-v1_crewscope-java-spring-v1", 1, HASH_B)));
        InjectionClaimedReferences empty = service().submitClaimed(
                context, ORGANIZATION_ID, TEAM_ID, TASK_ID, EXECUTION_ID, 2, List.of());

        assertEquals(ManifestSourceType.SKILL_INSTRUCTION,
                skillReceipt.claimed().get(0).type());
        assertEquals(List.of(), empty.claimed());
    }

    // ------------------------------------------------------------------ fixtures

    private static io.crewscope.domain.identity.Principal actor() {
        return io.crewscope.domain.identity.Principal.create(
                ACTOR,
                io.crewscope.domain.identity.PrincipalScope.organization(ORGANIZATION_ID),
                io.crewscope.domain.identity.PrincipalType.USER,
                Optional.empty(),
                "Actor",
                Optional.empty(),
                io.crewscope.domain.identity.PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private static WorkItemScope scope() {
        return new WorkItemScope(ORGANIZATION_ID, TEAM_ID, WorkspaceId.generate(),
                WorkProjectId.generate());
    }

    private static ManifestSourceKey knowledgeKey() {
        return new ManifestSourceKey(
                ManifestSourceType.KNOWLEDGE_ENTRY, "entry-1", 3, HASH_A);
    }

    private static ManifestSourceRef ref(
            ManifestSourceType type, String sourceId, long version, String hash,
            ManifestSourceStage stage) {
        return new ManifestSourceRef(type, sourceId, version, hash, stage);
    }

    private static InjectionManifest manifest(int attempt, ManifestSourceRef... refs) {
        return new InjectionManifest(
                InjectionManifestId.generate(), EXECUTION_ID, attempt, List.of(refs),
                List.of(), new PromptBudgetSnapshot(8192, 1, 0, 0), List.of(), NOW);
    }

    private static TransactionExecutor directTransactions() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
    }

    /** Minimal manifest store: attempt-keyed like the uk, execution-wide reads. */
    private static final class FakeManifestStore implements InjectionManifestRepository {

        private final Map<Integer, InjectionManifest> byAttempt = new LinkedHashMap<>();

        void store(InjectionManifest manifest) {
            byAttempt.put(manifest.attempt(), manifest);
        }

        @Override
        public InjectionManifest append(InjectionManifest manifest) {
            throw new UnsupportedOperationException("not part of the I02c read face");
        }

        @Override
        public Optional<InjectionManifest> findByAttempt(
                OrganizationId organizationId, TeamId teamId, TaskExecutionId executionId,
                int attempt) {
            return Optional.ofNullable(byAttempt.get(attempt));
        }

        @Override
        public List<InjectionManifest> findByExecution(
                OrganizationId organizationId, TeamId teamId, TaskExecutionId executionId) {
            return new ArrayList<>(byAttempt.values());
        }
    }

    /** Mirrors the adapter contract: feedback replays, claimed sets replay or conflict. */
    private static final class FakeReferenceStore implements InjectionReferenceRepository {

        private final Map<String, InjectionReferenceFeedback> feedbackRows =
                new LinkedHashMap<>();
        private final Map<Integer, InjectionClaimedReferences> claimedByAttempt =
                new LinkedHashMap<>();

        void storeFeedback(InjectionReferenceFeedback feedback) {
            feedbackRows.put(feedbackKey(feedback), feedback);
        }

        void storeClaimed(InjectionClaimedReferences receipt) {
            claimedByAttempt.put(receipt.attempt(), receipt);
        }

        @Override
        public InjectionReferenceFeedback record(InjectionReferenceFeedback feedback) {
            return feedbackRows.computeIfAbsent(feedbackKey(feedback), key -> feedback);
        }

        @Override
        public List<InjectionReferenceFeedback> findFeedback(
                OrganizationId organizationId, TeamId teamId, TaskExecutionId executionId,
                PrincipalId memberPrincipalId) {
            return feedbackRows.values().stream()
                    .filter(row -> row.memberPrincipalId().equals(memberPrincipalId))
                    .toList();
        }

        @Override
        public InjectionClaimedReferences recordClaimed(InjectionClaimedReferences receipt) {
            InjectionClaimedReferences stored = claimedByAttempt.get(receipt.attempt());
            if (stored != null) {
                if (stored.sameClaimAs(receipt)) {
                    return stored;
                }
                throw new InjectionClaimedReferenceConflictException(stored);
            }
            claimedByAttempt.put(receipt.attempt(), receipt);
            return receipt;
        }

        @Override
        public List<InjectionClaimedReferences> findClaimed(
                OrganizationId organizationId, TeamId teamId, TaskExecutionId executionId) {
            return new ArrayList<>(claimedByAttempt.values());
        }

        private static String feedbackKey(InjectionReferenceFeedback feedback) {
            return String.join("|",
                    feedback.source().type().name(), feedback.source().sourceId(),
                    String.valueOf(feedback.source().version()),
                    feedback.source().contentHash(),
                    feedback.memberPrincipalId().value().toString());
        }
    }
}
