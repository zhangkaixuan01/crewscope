package io.crewscope.application.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentTemplateHash;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.coding.CodingTargetSnapshotId;
import io.crewscope.domain.coding.CodingTargetSnapshotReference;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.DiffArtifactId;
import io.crewscope.domain.coding.DiffArtifactReference;
import io.crewscope.domain.coding.DiffFileEntry;
import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.coding.DiffManifest;
import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.coding.PatchArtifactReference;
import io.crewscope.domain.coding.RepositoryCommitId;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.responsibility.ReviewerPolicyViolationException;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.review.ReviewSubjectReference;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.review.ReviewerRelationship;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.RuntimeContentHash;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The Review Gate fixture shared by the M5-A05 command contracts and the M9 availability
 * reconciliation.
 *
 * <p>Both need the same story: one WorkItem whose Task attempt carries a ReviewRequest, a human
 * member who may or may not hold the Gate Reviewer responsibility, a Reviewer Agent who may or may
 * not hold the advisory one, and a duty-separation policy that may or may not refuse. Sharing it
 * keeps the commands' preconditions and the projection's verdicts anchored to identical facts, which
 * is the only way a disagreement between them reads as a real one.
 *
 * <p>The repositories are mocks on purpose: this fixture exists to drive the four real command
 * services down their real precondition paths, not to re-test persistence. Two stubs deserve their
 * own note, because they bound what any test built on this fixture may claim:
 *
 * <ul>
 *   <li>{@link ReviewRequest#start} and {@link ReviewRequest#complete} return the current state, so
 *       a mocked ReviewRequest does not have to be a whole real aggregate. Availability projects
 *       whether a command <em>accepts</em> an action; the aggregate's own transition is covered by
 *       {@code ReviewRequestTest}. Everything the Gate guards read is still real.
 *   <li>A mocked predecessor cannot serve {@link ReviewRequest#successor}, which reads its
 *       predecessor's state as a field rather than through an accessor. Re-review therefore cannot
 *       be driven to completion here, and its enabled direction is asserted only as "the command did
 *       not refuse it for a projected reason" — the gap is recorded in the API contract.
 * </ul>
 */
class ReviewGateCommandTestSupport {

    static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-17T09:00:00Z");

    final OrganizationId organizationId = OrganizationId.generate();
    final TeamId teamId = TeamId.generate();
    final WorkItemScope scope = new WorkItemScope(
            organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
    final WorkItem workItem = WorkItem.reconstitute(
            WorkItemId.generate(),
            scope,
            new WorkItemKey("CRW-500"),
            "Gate the review",
            WorkItemStatus.READY,
            4,
            AuditMetadata.createdBy(PrincipalId.generate(), NOW));
    final TaskId taskId = TaskId.generate();
    final TaskExecutionId executionId = TaskExecutionId.generate();
    final ReviewRequestId requestId = ReviewRequestId.generate();
    final ContextPackageId contextId = ContextPackageId.generate();
    final PolicySnapshotId policyId = PolicySnapshotId.generate();
    final AgentProfileId profileId = AgentProfileId.generate();
    final TeamMemberId actorMemberId = TeamMemberId.generate();
    final TeamMemberId ownerMemberId = TeamMemberId.generate();
    final PrincipalId ownerPrincipalId = PrincipalId.generate();

    final Principal actor = Principal.create(
            PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
            PrincipalType.USER, Optional.empty(), "Gate reviewer", Optional.empty(),
            PrincipalVisibility.TEAM, NOW);
    final Principal reviewerAgent = Principal.create(
            PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
            PrincipalType.SPECIALIST_AGENT, Optional.of(actor.id()), "Reviewer Agent",
            Optional.empty(), PrincipalVisibility.TEAM, NOW);

    final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    final TaskRepository tasks = mock(TaskRepository.class);
    final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
    final ReviewRequestRepository requests = mock(ReviewRequestRepository.class);
    final ContextPackageRepository contexts = mock(ContextPackageRepository.class);
    final PolicySnapshotRepository policySnapshots = mock(PolicySnapshotRepository.class);
    final PrincipalRepository principals = mock(PrincipalRepository.class);
    final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    final TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
    final ResponsibilityAssignmentRepository assignments =
            mock(ResponsibilityAssignmentRepository.class);
    final TaskAgentRuntimeSessionRepository sessions =
            mock(TaskAgentRuntimeSessionRepository.class);
    final DiffArtifactRepository diffs = mock(DiffArtifactRepository.class);
    final TestEvidenceRepository tests = mock(TestEvidenceRepository.class);
    final CommandEvidenceRepository commands = mock(CommandEvidenceRepository.class);
    final ReviewSubjectRepository subjects = mock(ReviewSubjectRepository.class);
    final ReviewFindingRepository findings = mock(ReviewFindingRepository.class);
    final ReviewDecisionRepository decisions = mock(ReviewDecisionRepository.class);
    final ReviewModificationRoundRepository rounds =
            mock(ReviewModificationRoundRepository.class);
    final ContextPackageBuilder contextBuilder = mock(ContextPackageBuilder.class);
    final ReviewerExecutionPort runtime = mock(ReviewerExecutionPort.class);
    final ReviewFindingBatchRecorder recorder = mock(ReviewFindingBatchRecorder.class);
    final ReviewEventPublisher events = mock(ReviewEventPublisher.class);
    final ReviewQueryRepository queries = mock(ReviewQueryRepository.class);
    final io.crewscope.application.command.CommandReceiptStore receipts =
            mock(io.crewscope.application.command.CommandReceiptStore.class);
    final GateReviewerPolicyProvider reviewerPolicies =
            mock(GateReviewerPolicyProvider.class);

    final ReviewGateApplicationService gate;
    final ReviewerExecutionApplicationService reviewer;
    final ReviewRequestApplicationService requestService;

    private final TeamMember actorMember = mock(TeamMember.class);
    private final TeamMember ownerMember = mock(TeamMember.class);
    private final ReviewerEligibilityPolicy eligibility =
            mock(ReviewerEligibilityPolicy.class);
    private final List<ResponsibilityAssignment> currentAssignments = new ArrayList<>();

    ReviewGateCommandTestSupport() {
        when(accessPolicy.requireVisibleWorkItem(any(), any(), any(), any(), any()))
                .thenReturn(workItem);

        Task task = mock(Task.class);
        when(task.id()).thenReturn(taskId);
        when(task.scope()).thenReturn(scope);
        when(task.workItemId()).thenReturn(workItem.id());
        when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
        when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));

        TaskExecution execution = mock(TaskExecution.class);
        when(execution.id()).thenReturn(executionId);
        when(execution.taskId()).thenReturn(taskId);
        when(execution.scope()).thenReturn(scope);
        when(execution.attempt()).thenReturn(1);
        when(executions.findById(organizationId, executionId))
                .thenReturn(Optional.of(execution));

        when(actorMember.id()).thenReturn(actorMemberId);
        when(actorMember.userPrincipalId()).thenReturn(actor.id());
        when(actorMember.canParticipate()).thenReturn(true);
        when(ownerMember.id()).thenReturn(ownerMemberId);
        when(ownerMember.canParticipate()).thenReturn(true);
        when(memberships.findByTeam(organizationId, teamId))
                .thenReturn(List.of(actorMember, ownerMember));

        when(principals.findById(organizationId, reviewerAgent.id()))
                .thenReturn(Optional.of(reviewerAgent));
        AgentProfile profile = mock(AgentProfile.class);
        when(profile.id()).thenReturn(profileId);
        when(profile.version()).thenReturn(3L);
        when(profile.agentPrincipalId()).thenReturn(reviewerAgent.id());
        when(profile.status()).thenReturn(AgentProfileStatus.ACTIVE);
        when(profiles.findById(organizationId, profileId)).thenReturn(Optional.of(profile));

        when(reviewerPolicies.resolve(workItem)).thenReturn(eligibility);

        when(receipts.reserve(any()))
                .thenReturn(io.crewscope.application.command.CommandReservation.newlyAcquired());
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(events.requestCreated(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(events.requestStarted(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(events.requestCompleted(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(events.decisionRecorded(any(), any(), any())).thenReturn(UUID.randomUUID());
        when(runtime.execute(any())).thenReturn(CompletableFuture.completedFuture(List.of()));

        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };

        gate = new ReviewGateApplicationService(
                accessPolicy, tasks, executions, memberships, assignments, reviewerPolicies,
                requests, contexts, decisions, rounds, queries, events, receipts,
                transactions, () -> NOW);
        reviewer = new ReviewerExecutionApplicationService(
                accessPolicy, tasks, executions, requests, contexts, policySnapshots,
                principals, profiles, memberships, assignments, sessions, runtime, recorder,
                events, queries, receipts, transactions, () -> NOW);
        requestService = new ReviewRequestApplicationService(
                accessPolicy, tasks, executions, diffs, tests, commands, policySnapshots,
                principals, profiles, memberships, assignments, subjects, contexts, requests,
                findings, decisions, rounds, queries, contextBuilder,
                new ReviewGateAvailabilityProjector(), reviewerPolicies, events, receipts,
                transactions, () -> NOW);
    }

    /**
     * Prepares one cell: the ReviewRequest status, which Reviewer responsibilities are held, and
     * whether the duty-separation policy refuses.
     *
     * <p>Rebuilt for every cell because the commands read state the previous cell already consulted,
     * and a verdict judged from another cell's aftermath would be no verdict at all.
     */
    void arrange(
            ReviewRequestStatus status,
            boolean reviewerAgentAssigned,
            boolean gateReviewerAssigned,
            boolean dutySeparated) {
        currentAssignments.clear();
        // The attempt always has one member Owner: Re-review resolves the subject Owner before it
        // can refuse anything, and a fixture without one would refuse earlier than the rule under
        // test. It is not a Reviewer row, so it changes no Gate verdict.
        currentAssignments.add(assignment(ResponsibilityRole.OWNER, ownerPrincipalId,
                PrincipalType.USER, Optional.of(ownerMemberId)));
        if (reviewerAgentAssigned) {
            currentAssignments.add(assignment(ResponsibilityRole.REVIEWER, reviewerAgent.id(),
                    PrincipalType.SPECIALIST_AGENT, Optional.empty()));
        }
        if (gateReviewerAssigned) {
            currentAssignments.add(assignment(ResponsibilityRole.REVIEWER, actor.id(),
                    PrincipalType.USER, Optional.of(actorMemberId)));
        }
        when(assignments.findActiveByWorkItem(organizationId, workItem.id()))
                .thenReturn(List.copyOf(currentAssignments));

        // doReturn/doThrow rather than when(...).thenX: the cells are arranged on one fixture, and
        // re-stubbing a method through when(...) would invoke the stub being replaced — for a
        // refusing cell that means throwing its refusal out of the next cell's arrangement.
        if (dutySeparated) {
            doReturn(ReviewerEligibilityDecision.strict())
                    .when(eligibility).evaluateGate(any(), any(), any(), any(), any());
        } else {
            doThrow(new ReviewerPolicyViolationException(
                    workItem.id(), actorMemberId,
                    java.util.Set.of(ResponsibilityRole.OWNER), 2, false))
                    .when(eligibility).evaluateGate(any(), any(), any(), any(), any());
        }

        ReviewRequest request = request(status);
        when(requests.findById(organizationId, requestId)).thenReturn(Optional.of(request));
        // Re-review resolves the predecessor before it can refuse anything, so the predecessor has
        // to exist for the status gate to be the first refusal it can raise.
        when(requests.findCurrentByExecution(organizationId, executionId, 1))
                .thenReturn(Optional.of(request));

        ContextPackage context = mock(ContextPackage.class);
        when(context.contextHash()).thenReturn(TaskFactHash.sha256("review-context"));
        when(contexts.findById(organizationId, contextId)).thenReturn(Optional.of(context));

        PolicySnapshot policy = mock(PolicySnapshot.class);
        ResolvedAgentExecutionConfiguration resolved = resolvedReviewerConfiguration();
        when(policy.id()).thenReturn(policyId);
        when(policy.scope()).thenReturn(scope);
        when(policy.taskId()).thenReturn(taskId);
        when(policy.executionId()).thenReturn(executionId);
        when(policy.revision()).thenReturn(2L);
        when(policy.snapshotHash()).thenReturn(TaskFactHash.sha256("review-policy"));
        when(policy.agentExecutionConfiguration()).thenReturn(Optional.of(resolved));
        when(policySnapshots.findById(organizationId, policyId)).thenReturn(Optional.of(policy));

        arrangeCreationEvidence();

        TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
        when(session.canInvoke()).thenReturn(true);
        when(session.purpose()).thenReturn(TaskAgentSessionPurpose.SPECIALIST);
        when(session.agentPrincipalId()).thenReturn(reviewerAgent.id());
        when(session.agentProfileId()).thenReturn(profileId);
        when(session.agentProfileVersion()).thenReturn(3L);
        when(sessions.findByExecution(organizationId, executionId))
                .thenReturn(List.of(session));

        when(recorder.record(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(new ReviewFindingBatchResult(
                        List.of(), List.of(), List.of(),
                        new ReviewRepairRequestSummary(
                                requestId.toString(), 1, TaskFactHash.sha256("review-context"),
                                ReviewerRelationship.INDEPENDENT, false, List.of())));
    }

    /** The facts the projection reads, resolved the way the same cell was arranged. */
    ReviewGateFacts facts(ReviewRequestStatus status, boolean reviewerAgentAssigned,
            boolean gateReviewerAssigned, boolean dutySeparated) {
        return new ReviewGateFacts(
                status,
                reviewerAgentAssigned,
                gateReviewerAssigned,
                dutySeparated,
                Optional.of(ReviewWorkbenchCoordinates.assignReviewer(workItem)));
    }

    ReviewGateAction projected(ReviewGateFacts facts, String actionId) {
        return new ReviewGateAvailabilityProjector().all(facts).stream()
                .filter(action -> action.actionId().equals(actionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Unknown Gate action " + actionId));
    }

    TeamCommandContext context(String key) {
        return new TeamCommandContext(
                new TeamAccessContext(actor, false),
                IdempotencyKey.from("m9-a05/" + key),
                UUID.randomUUID(),
                Optional.empty());
    }

    /**
     * The Diff and TestEvidence a Re-review resolves before it looks at the ReviewRequest status.
     *
     * <p>Stubbed for every cell, not only Re-review: the exact same facts exist by the time any Gate
     * action is offered in production, and a fixture that withheld them would refuse earlier than the
     * rule under test.
     */
    private void arrangeCreationEvidence() {
        CodingTargetSnapshotReference target = new CodingTargetSnapshotReference(
                CodingTargetSnapshotId.generate(), 1, TaskFactHash.sha256("coding-target"));
        DiffGeneration generation = mock(DiffGeneration.class);
        RuntimeContentHash contentHash = mock(RuntimeContentHash.class);

        DiffArtifact diff = mock(DiffArtifact.class);
        DiffManifest manifest = mock(DiffManifest.class);
        when(manifest.generation()).thenReturn(generation);
        when(manifest.contentHash()).thenReturn(contentHash);
        DiffFileEntry changedFile = mock(DiffFileEntry.class);
        when(changedFile.path()).thenReturn(new DiffPath("src/main/java/Example.java"));
        when(manifest.files()).thenReturn(List.of(changedFile));
        DiffArtifactId diffId = DiffArtifactId.generate();
        when(diff.id()).thenReturn(diffId);
        when(diff.scope()).thenReturn(scope);
        when(diff.taskId()).thenReturn(taskId);
        when(diff.taskExecutionId()).thenReturn(executionId);
        when(diff.attempt()).thenReturn(1);
        when(diff.reference()).thenReturn(
                new DiffArtifactReference(diffId, TaskFactHash.sha256("diff-final")));
        when(diff.baselineCommit()).thenReturn(new RepositoryCommitId("0".repeat(40)));
        when(diff.deliveryCommit()).thenReturn(new RepositoryCommitId("1".repeat(40)));
        when(diff.patchArtifact()).thenReturn(new PatchArtifactReference(
                io.crewscope.domain.shared.id.ArtifactId.generate(),
                0, RuntimeContentHash.sha256("")));
        when(diff.codingTarget()).thenReturn(target);
        when(diff.manifest()).thenReturn(manifest);
        when(diffs.findByTaskExecution(organizationId, teamId, scope.projectId(), executionId))
                .thenReturn(Optional.of(diff));

        TestEvidence test = mock(TestEvidence.class);
        when(test.attempt()).thenReturn(1);
        when(test.codingTarget()).thenReturn(target);
        when(test.diffGeneration()).thenReturn(generation);
        when(test.diffManifestHash()).thenReturn(contentHash);
        when(test.commands()).thenReturn(List.of());
        when(tests.findByTaskExecution(organizationId, teamId, scope.projectId(), executionId))
                .thenReturn(List.of(test));
    }

    private ResolvedAgentExecutionConfiguration resolvedReviewerConfiguration() {
        AgentTemplateVersion templateVersion = mock(AgentTemplateVersion.class);
        when(templateVersion.key()).thenReturn(new AgentTemplateKey("reviewer"));
        AgentOwnership ownership = mock(AgentOwnership.class);
        when(ownership.ownerMemberId()).thenReturn(Optional.empty());
        ResolvedAgentExecutionConfiguration resolved =
                mock(ResolvedAgentExecutionConfiguration.class);
        when(resolved.templateVersion()).thenReturn(templateVersion);
        when(resolved.templateContentHash()).thenReturn(mock(AgentTemplateHash.class));
        when(resolved.configurationRevision()).thenReturn(mock(AgentConfigurationRevision.class));
        when(resolved.configurationHash()).thenReturn(mock(AgentConfigurationHash.class));
        when(resolved.agentPrincipalId()).thenReturn(reviewerAgent.id());
        when(resolved.agentProfileId()).thenReturn(profileId);
        when(resolved.agentProfileVersion()).thenReturn(3L);
        when(resolved.ownership()).thenReturn(ownership);
        return resolved;
    }

    private ReviewRequest request(ReviewRequestStatus status) {
        ReviewRequest request = mock(ReviewRequest.class);
        when(request.id()).thenReturn(requestId);
        when(request.scope()).thenReturn(scope);
        when(request.taskId()).thenReturn(taskId);
        when(request.taskExecutionId()).thenReturn(executionId);
        when(request.attempt()).thenReturn(1);
        when(request.revision()).thenReturn(1L);
        when(request.version()).thenReturn(1L);
        when(request.status()).thenReturn(status);
        when(request.requestHash()).thenReturn(TaskFactHash.sha256("review-request"));
        when(request.subject()).thenReturn(mock(ReviewSubjectReference.class));
        ContextPackageReference reference = mock(ContextPackageReference.class);
        when(reference.id()).thenReturn(contextId);
        when(reference.version()).thenReturn(1L);
        when(reference.contextHash()).thenReturn(TaskFactHash.sha256("review-context"));
        when(request.contextPackage()).thenReturn(reference);
        ReviewerExecutionReference reviewer = mock(ReviewerExecutionReference.class);
        when(reviewer.agentPrincipalId()).thenReturn(reviewerAgent.id());
        when(reviewer.agentProfileId()).thenReturn(profileId);
        when(reviewer.agentProfileVersion()).thenReturn(3L);
        when(reviewer.policySnapshotId()).thenReturn(policyId);
        when(reviewer.policySnapshotRevision()).thenReturn(2L);
        when(reviewer.policySnapshotHash()).thenReturn(TaskFactHash.sha256("review-policy"));
        when(reviewer.reviewerOwnerMemberId()).thenReturn(Optional.of(ownerMemberId));
        when(request.reviewer()).thenReturn(reviewer);
        when(request.start(any(), anyLong(), any(), any())).thenReturn(request);
        when(request.complete(any(), anyLong(), any(), any())).thenReturn(request);
        return request;
    }

    private static ResponsibilityAssignment assignment(
            ResponsibilityRole role,
            PrincipalId principalId,
            PrincipalType actorType,
            Optional<TeamMemberId> memberId) {
        ResponsibilityAssignment value = mock(ResponsibilityAssignment.class);
        when(value.isActive()).thenReturn(true);
        when(value.role()).thenReturn(role);
        when(value.actorType()).thenReturn(actorType);
        when(value.actorPrincipalId()).thenReturn(principalId);
        when(value.actorMemberId()).thenReturn(memberId);
        return value;
    }
}
