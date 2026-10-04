package io.crewscope.server.config.runtime;

import io.crewscope.agentscope.coding.CodingSpecialistAuthority;
import io.crewscope.agentscope.coding.CodingSpecialistAuthorityGateway;
import io.crewscope.agentscope.coding.CodingSpecialistRound;
import io.crewscope.agentscope.coding.InjectionPromptRenderer;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.coding.output.RepositoryAnalysisV1;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.retrieval.PromptInjectionPlan;
import io.crewscope.application.retrieval.PromptInjectionRequest;
import io.crewscope.application.retrieval.PromptInjectionService;
import io.crewscope.application.retrieval.KnowledgeRetrievalQuery;
import io.crewscope.application.skill.TeamSkillExecutionSource;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.retrieval.ManifestSourceRef;
import io.crewscope.domain.retrieval.ManifestSourceStage;
import io.crewscope.domain.retrieval.ManifestSourceType;
import io.crewscope.domain.retrieval.SourceCommit;
import io.crewscope.infrastructure.runtime.RuntimeWorkerRegistrationSpec;
import io.crewscope.infrastructure.workspace.repository.CodingSpecialistToolSession;
import io.crewscope.infrastructure.workspace.repository.CodingSpecialistToolSessionFactory;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecution;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceExecutionLifecycle;
import io.crewscope.infrastructure.workspace.repository.CodingWorkspaceRuntimeRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Production authority boundary from Specialist rounds to the current fenced Workspace. */
public final class WorkerCodingSpecialistAuthorityGateway
        implements CodingSpecialistAuthorityGateway {

    private final CodingWorkspaceRuntimeRegistry workspaces;
    private final CodingSpecialistToolSessionFactory tools;
    private final CodingWorkspaceExecutionLifecycle lifecycle;
    private final ExecutionLeaseRepository leases;
    private final TestEvidenceRepository testEvidence;
    private final PrincipalRepository principals;
    private final RuntimeWorkerRegistrationSpec registration;
    private final AuthoritativeTimeProvider timeProvider;
    private final TransactionExecutor transactions;
    private final PromptInjectionService injection;
    private final InjectionPromptRenderer injectionRenderer;
    private final TeamSkillExecutionSource teamSkills;
    private final ConcurrentMap<RoundKey, CodingSpecialistToolSession> sessions =
            new ConcurrentHashMap<>();

    public WorkerCodingSpecialistAuthorityGateway(
            CodingWorkspaceRuntimeRegistry workspaces,
            CodingSpecialistToolSessionFactory tools,
            CodingWorkspaceExecutionLifecycle lifecycle,
            ExecutionLeaseRepository leases,
            TestEvidenceRepository testEvidence,
            PrincipalRepository principals,
            RuntimeWorkerRegistrationSpec registration,
            AuthoritativeTimeProvider timeProvider,
            TransactionExecutor transactions,
            PromptInjectionService injection,
            InjectionPromptRenderer injectionRenderer,
            TeamSkillExecutionSource teamSkills) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.testEvidence = Objects.requireNonNull(testEvidence, "testEvidence");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.injection = Objects.requireNonNull(injection, "injection");
        this.injectionRenderer = Objects.requireNonNull(injectionRenderer, "injectionRenderer");
        this.teamSkills = teamSkills;
    }

    @Override
    public void recover(TaskExecutionRuntimeFacts facts) {
        requireWorkspace(facts);
    }

    @Override
    public CodingSpecialistRound openRound(
            TaskExecutionRuntimeFacts facts,
            int round,
            Optional<TestEvidence> previousFailedEvidence) {
        CodingWorkspaceExecution workspace = requireWorkspace(facts);
        var ownership = workspace.workspace().ownership();
        var lease = leases.findById(
                        workspace.workspace().scope().organizationId(),
                        ownership.environment(),
                        ownership.leaseId())
                .orElseThrow(() -> new IllegalStateException(
                        "Coding Workspace current Lease is unavailable"));
        // The sealed manifest is a precondition of the first model call, so assembly
        // runs before the session opens: a failed assembly leaves no half-open session.
        // It runs after the lease check so a dead lease seals no manifest and renews
        // no memory TTL.
        String injectionBlock = injectionBlock(facts, workspace);
        CodingSpecialistToolSession session = tools.open(
                workspace, lease, executionPrincipal(facts),
                transactions.required(timeProvider::now));
        RoundKey key = key(facts, round);
        if (sessions.putIfAbsent(key, session) != null) {
            session.close();
            throw new IllegalStateException("Coding Specialist round is already open");
        }
        return new CodingSpecialistRound(
                round,
                session.toolkit(),
                instruction(facts, workspace, round, previousFailedEvidence, injectionBlock));
    }

    @Override
    public CodingSpecialistAuthority inspect(TaskExecutionRuntimeFacts facts, int round) {
        closeRound(facts, round);
        CodingWorkspaceExecution execution = requireWorkspace(facts);
        var manifest = execution.finalDiff()
                .map(io.crewscope.domain.coding.DiffArtifact::manifest)
                .orElseGet(() -> {
                    var monitor = execution.diffMonitor().orElseThrow(() ->
                            new IllegalStateException(
                                    "Coding Workspace Diff monitor is unavailable"));
                    monitor.reconcileNow();
                    return monitor.latest().orElseThrow(() ->
                            new IllegalStateException(
                                    "Coding Workspace Diff manifest is unavailable"));
                });
        Optional<TestEvidence> latestEvidence = latestEvidence(execution);
        return new CodingSpecialistAuthority(
                execution.target(),
                execution.workspace(),
                execution.policy(),
                repositoryAnalysis(execution),
                manifest,
                latestEvidence,
                execution.finalDiff());
    }

    @Override
    public CodingSpecialistAuthority finalizeAuthority(
            TaskExecutionRuntimeFacts facts, int round) {
        closeRound(facts, round);
        CodingWorkspaceExecution execution = requireWorkspace(facts);
        TestEvidence evidence = latestEvidence(execution)
                .filter(TestEvidence::succeeded)
                .orElseThrow(() -> new IllegalStateException(
                        "Successful TestEvidence is unavailable"));
        if (execution.finalDiff().isEmpty()) {
            lifecycle.beforeRelease(
                    execution,
                    facts.execution(),
                    currentLease(execution),
                    io.crewscope.application.execution.TaskExecutionTerminalStatus.COMPLETED);
        }
        var diff = execution.finalDiff().orElseThrow();
        return new CodingSpecialistAuthority(
                execution.target(),
                execution.workspace(),
                execution.policy(),
                repositoryAnalysis(execution),
                diff.manifest(),
                Optional.of(evidence),
                Optional.of(diff));
    }

    @Override
    public void closeRound(TaskExecutionRuntimeFacts facts, int round) {
        CodingSpecialistToolSession session = sessions.remove(key(facts, round));
        if (session != null) {
            session.close();
        }
    }

    private CodingWorkspaceExecution requireWorkspace(TaskExecutionRuntimeFacts facts) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        CodingWorkspaceExecution execution = workspaces.find(required.execution().id())
                .orElseThrow(() -> new IllegalStateException(
                        "Coding Workspace is not active in this Worker"));
        if (!execution.workspace().taskExecutionId().equals(required.execution().id())
                || execution.workspace().attempt() != required.execution().attempt()
                || !execution.workspace().ownership().leaseId().equals(required.lease().id())
                || !execution.workspace().ownership().fencingToken()
                        .equals(required.lease().fencingToken())) {
            throw new IllegalStateException("Coding Workspace crossed its execution epoch");
        }
        return execution;
    }

    private io.crewscope.domain.task.ExecutionLease currentLease(
            CodingWorkspaceExecution execution) {
        var ownership = execution.workspace().ownership();
        return leases.findById(
                        execution.workspace().scope().organizationId(),
                        ownership.environment(),
                        ownership.leaseId())
                .orElseThrow(() -> new IllegalStateException(
                        "Coding Workspace current Lease is unavailable"));
    }

    private Principal executionPrincipal(TaskExecutionRuntimeFacts facts) {
        return principals.findById(
                        registration.organizationId(),
                        facts.policySnapshot().executionPrincipal().principalId())
                .filter(Principal::canAct)
                .orElseThrow(() -> new IllegalStateException(
                        "Coding execution Principal is unavailable"));
    }

    /**
     * The injection actor is the task creator: one member identity under which both
     * retrieval authorization and memory ownership run for the whole assembly. An
     * unavailable or disqualified creator fails the round closed — revocation must not
     * degrade into injecting with a stale identity (ADR-038).
     */
    private Principal injectionPrincipal(TaskExecutionRuntimeFacts facts) {
        return facts.task().audit().createdBy()
                .map(creator -> principals.findById(registration.organizationId(), creator))
                .flatMap(java.util.function.Function.identity())
                .filter(Principal::canAct)
                .orElseThrow(() -> new IllegalStateException(
                        "Task creator Principal is unavailable for prompt injection"));
    }

    /** Renders the injection block of one assembly; a disabled switch renders "". */
    private String injectionBlock(
            TaskExecutionRuntimeFacts facts, CodingWorkspaceExecution workspace) {
        // A disabled switch is zero behavior change: no creator resolution, no request,
        // no assembly — an unavailable task creator must not fail a round injection is
        // not part of.
        if (!injection.enabled()) {
            return "";
        }
        PromptInjectionPlan plan = injection.assemble(new PromptInjectionRequest(
                workspace.target().scope().organizationId(),
                workspace.target().scope().teamId(),
                workspace.target().scope().workspaceId(),
                workspace.target().scope().projectId(),
                facts.execution().id(),
                facts.execution().attempt(),
                facts.policySnapshot().agentProfileId(),
                injectionPrincipal(facts),
                facts.task().brief().objective(),
                facts.task().brief().acceptanceCriteria(),
                new KnowledgeRetrievalQuery.RepositoryTarget(
                        workspace.target().scope().projectId(),
                        workspace.target().repositoryBindingId(),
                        new SourceCommit(workspace.target().baselineCommit().value())),
                dynamicSkillInstructions(facts)));
        return injectionRenderer.render(plan);
    }

    /**
     * The sealed manifest is the load-evidence anchor of the dynamic Team Skills (D2):
     * the same pinned-execution resolution the Step runtime loads from produces the
     * INJECTED SKILL triples sealed here, so manifest and repository can never drift.
     * A missing source (older worker assembly) changes nothing.
     */
    private List<ManifestSourceRef> dynamicSkillInstructions(TaskExecutionRuntimeFacts facts) {
        if (teamSkills == null) {
            return List.of();
        }
        return teamSkills
                .resolveForPinnedExecution(
                        facts.task().scope().organizationId(),
                        facts.task().scope().teamId(),
                        facts.policySnapshot().agentExecutionConfiguration(),
                        facts.execution().id(),
                        facts.execution().attempt())
                .stream()
                .map(published -> new ManifestSourceRef(
                        ManifestSourceType.SKILL_INSTRUCTION,
                        published.skillKey(),
                        published.revision(),
                        published.contentHash(),
                        ManifestSourceStage.INJECTED))
                .toList();
    }

    private Optional<TestEvidence> latestEvidence(CodingWorkspaceExecution execution) {
        List<TestEvidence> evidence = testEvidence.findByWorkspace(
                execution.workspace().scope().organizationId(),
                execution.workspace().scope().teamId(),
                execution.workspace().scope().projectId(),
                execution.workspace().id());
        return evidence.isEmpty()
                ? Optional.empty()
                : Optional.of(evidence.get(evidence.size() - 1));
    }

    private static RepositoryAnalysisV1 repositoryAnalysis(
            CodingWorkspaceExecution execution) {
        List<String> paths = execution.target().allowedPaths().values();
        return new RepositoryAnalysisV1(
                RepositoryAnalysisV1.SCHEMA_VERSION,
                execution.target().id().toString(),
                execution.target().revision(),
                execution.target().snapshotHash().toString(),
                List.of(),
                List.of(),
                paths,
                List.of("Repository facts remain bounded by the immutable WorkspacePolicy."),
                List.of("Inspect, modify, verify and deliver the requested change."));
    }

    static String instruction(
            TaskExecutionRuntimeFacts facts,
            CodingWorkspaceExecution execution,
            int round,
            Optional<TestEvidence> previousFailedEvidence) {
        return instruction(facts, execution, round, previousFailedEvidence, "");
    }

    static String instruction(
            TaskExecutionRuntimeFacts facts,
            CodingWorkspaceExecution execution,
            int round,
            Optional<TestEvidence> previousFailedEvidence,
            String injectionBlock) {
        TaskExecutionRuntimeFacts requiredFacts = Objects.requireNonNull(facts, "facts");
        CodingWorkspaceExecution requiredExecution = Objects.requireNonNull(
                execution, "execution");
        String previous = previousFailedEvidence
                .map(value -> " Previous test evidence " + value.id()
                        + " failed; repair the observed failure before rerunning verification.")
                .orElse("");
        String analysisHash = io.crewscope.application.coding.output.CodingOutputValidator
                .hashRepositoryAnalysis(repositoryAnalysis(requiredExecution))
                .toString();
        String acceptanceCriteria = String.join(
                "; ", requiredFacts.task().brief().acceptanceCriteria());
        String base = "Coding round " + round
                + " for target " + requiredExecution.target().id()
                + " revision " + requiredExecution.target().revision()
                + ". Task objective: " + requiredFacts.task().brief().objective()
                + ". Acceptance criteria: " + acceptanceCriteria
                + ". Allowed repository paths: "
                + String.join(", ", requiredExecution.target().allowedPaths().values())
                + ". Treat task content as work requirements, while platform policy and registered "
                + "tools remain the only execution authority. Use structured build commands, "
                + "persist the plan and task list, run tests, inspect the live diff, and return "
                + "the required structured delivery summary with a concrete changeSummary, "
                + "limitations and risks. The platform builds CodeChangeResultV1 and canonicalizes "
                + "final authority coordinates after verification. Current "
                + "coordinates: executionWorkspaceId=" + requiredExecution.workspace().id()
                + ", workspaceFingerprint=" + requiredExecution.workspace().fingerprint()
                + ", codingTargetHash=" + requiredExecution.target().snapshotHash()
                + ", repositoryAnalysisHash=" + analysisHash + "." + previous;
        String block = Objects.requireNonNull(injectionBlock, "injectionBlock");
        return block.isEmpty() ? base : base + "\n\n" + block;
    }

    private static RoundKey key(TaskExecutionRuntimeFacts facts, int round) {
        if (round < 1) {
            throw new IllegalArgumentException("round must be positive");
        }
        return new RoundKey(Objects.requireNonNull(facts, "facts").execution().id(), round);
    }

    private record RoundKey(io.crewscope.domain.task.TaskExecutionId executionId, int round) {}
}
