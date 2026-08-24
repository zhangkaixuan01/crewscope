package io.crewscope.application.review;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.identity.PrincipalRepository;
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
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.PolicySnapshotId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** M5-A05 Reviewer execution authorization, ETag and Receipt replay tests. */
class ReviewerExecutionApplicationServiceM5A05Test {

    @Test
    void rechecksReviewerAuthorityBeforeReturningAnIdempotentReplay() throws Exception {
        Fixture fixture = new Fixture(true);
        CommandReceipt receipt = fixture.receipt();
        when(fixture.receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(receipt));

        ReviewerExecutionResult result = fixture.service.execute(
                        fixture.commandContext(), fixture.teamId, fixture.taskId,
                        fixture.executionId, fixture.requestId, 1)
                .toCompletableFuture().get();

        assertTrue(result.replayed());
        verify(fixture.runtime, never()).execute(any());
        verify(fixture.assignments).findActiveByWorkItem(
                fixture.organizationId, fixture.workItemId);
    }

    @Test
    void rejectsAReviewerWhoseOwnerLeftEvenWhenAReceiptExists() {
        Fixture fixture = new Fixture(false);
        when(fixture.receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(fixture.receipt()));

        assertThrows(
                DomainValidationException.class,
                () -> fixture.service.execute(
                        fixture.commandContext(), fixture.teamId, fixture.taskId,
                        fixture.executionId, fixture.requestId, 1));
        verify(fixture.runtime, never()).execute(any());
    }

    @Test
    void rejectsAStaleStrongEtagBeforeCallingAgentScope() {
        Fixture fixture = new Fixture(true);
        when(fixture.receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThrows(
                OptimisticLockConflictException.class,
                () -> fixture.service.execute(
                        fixture.commandContext(), fixture.teamId, fixture.taskId,
                        fixture.executionId, fixture.requestId, 9));
        verify(fixture.runtime, never()).execute(any());
    }

    private static final class Fixture {
        private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T16:30:00Z");

        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        private final WorkItemId workItemId = WorkItemId.generate();
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final ReviewRequestId requestId = ReviewRequestId.generate();
        private final ContextPackageId contextId = ContextPackageId.generate();
        private final PolicySnapshotId policyId = PolicySnapshotId.generate();
        private final AgentProfileId profileId = AgentProfileId.generate();
        private final TeamMemberId ownerMemberId = TeamMemberId.generate();
        private final Principal actor = Principal.create(
                PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER, Optional.empty(), "Review operator", Optional.empty(),
                PrincipalVisibility.TEAM, NOW);
        private final Principal reviewerAgent = Principal.create(
                PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
                PrincipalType.SPECIALIST_AGENT, Optional.of(actor.id()), "Reviewer Agent",
                Optional.empty(), PrincipalVisibility.TEAM, NOW);

        private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
        private final TaskRepository tasks = mock(TaskRepository.class);
        private final TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
        private final ReviewRequestRepository requests = mock(ReviewRequestRepository.class);
        private final ContextPackageRepository contexts = mock(ContextPackageRepository.class);
        private final PolicySnapshotRepository policies = mock(PolicySnapshotRepository.class);
        private final PrincipalRepository principals = mock(PrincipalRepository.class);
        private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
        private final TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
        private final ResponsibilityAssignmentRepository assignments =
                mock(ResponsibilityAssignmentRepository.class);
        private final TaskAgentRuntimeSessionRepository sessions =
                mock(TaskAgentRuntimeSessionRepository.class);
        private final ReviewerExecutionPort runtime = mock(ReviewerExecutionPort.class);
        private final ReviewFindingBatchRecorder recorder = mock(ReviewFindingBatchRecorder.class);
        private final ReviewEventPublisher events = mock(ReviewEventPublisher.class);
        private final ReviewQueryRepository queries = mock(ReviewQueryRepository.class);
        private final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
        private final TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        private final ReviewerExecutionApplicationService service;

        private Fixture(boolean ownerActive) {
            Task task = mock(Task.class);
            when(task.id()).thenReturn(taskId);
            when(task.scope()).thenReturn(scope);
            when(task.workItemId()).thenReturn(workItemId);
            when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
            when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
            TaskExecution execution = mock(TaskExecution.class);
            when(execution.id()).thenReturn(executionId);
            when(execution.taskId()).thenReturn(taskId);
            when(execution.scope()).thenReturn(scope);
            when(execution.attempt()).thenReturn(1);
            when(executions.findById(organizationId, executionId))
                    .thenReturn(Optional.of(execution));

            ReviewRequest request = mock(ReviewRequest.class);
            when(request.id()).thenReturn(requestId);
            when(request.scope()).thenReturn(scope);
            when(request.taskId()).thenReturn(taskId);
            when(request.taskExecutionId()).thenReturn(executionId);
            when(request.attempt()).thenReturn(1);
            when(request.version()).thenReturn(1L);
            when(request.status()).thenReturn(ReviewRequestStatus.IN_PROGRESS);
            ContextPackageReference contextReference = mock(ContextPackageReference.class);
            when(contextReference.id()).thenReturn(contextId);
            when(request.contextPackage()).thenReturn(contextReference);
            ReviewerExecutionReference reviewer = mock(ReviewerExecutionReference.class);
            when(reviewer.agentPrincipalId()).thenReturn(reviewerAgent.id());
            when(reviewer.agentProfileId()).thenReturn(profileId);
            when(reviewer.agentProfileVersion()).thenReturn(3L);
            when(reviewer.policySnapshotId()).thenReturn(policyId);
            when(reviewer.policySnapshotRevision()).thenReturn(2L);
            when(reviewer.policySnapshotHash()).thenReturn(TaskFactHash.sha256("review-policy"));
            when(reviewer.reviewerOwnerMemberId()).thenReturn(Optional.of(ownerMemberId));
            when(request.reviewer()).thenReturn(reviewer);
            when(requests.findById(organizationId, requestId)).thenReturn(Optional.of(request));
            when(contexts.findById(organizationId, contextId))
                    .thenReturn(Optional.of(mock(ContextPackage.class)));
            when(principals.findById(organizationId, reviewerAgent.id()))
                    .thenReturn(Optional.of(reviewerAgent));

            AgentProfile profile = mock(AgentProfile.class);
            when(profile.id()).thenReturn(profileId);
            when(profile.version()).thenReturn(3L);
            when(profile.agentPrincipalId()).thenReturn(reviewerAgent.id());
            when(profiles.findById(organizationId, profileId)).thenReturn(Optional.of(profile));
            ResponsibilityAssignment reviewerAssignment = mock(ResponsibilityAssignment.class);
            when(reviewerAssignment.role()).thenReturn(ResponsibilityRole.REVIEWER);
            when(reviewerAssignment.actorPrincipalId()).thenReturn(reviewerAgent.id());
            when(assignments.findActiveByWorkItem(organizationId, workItemId))
                    .thenReturn(List.of(reviewerAssignment));
            if (ownerActive) {
                var member = mock(io.crewscope.domain.team.TeamMember.class);
                when(member.id()).thenReturn(ownerMemberId);
                when(member.canParticipate()).thenReturn(true);
                when(memberships.findByTeam(organizationId, teamId))
                        .thenReturn(List.of(member));
            } else {
                when(memberships.findByTeam(organizationId, teamId)).thenReturn(List.of());
            }

            PolicySnapshot policy = mock(PolicySnapshot.class);
            when(policy.revision()).thenReturn(2L);
            when(policy.snapshotHash()).thenReturn(TaskFactHash.sha256("review-policy"));
            when(policies.findById(organizationId, policyId)).thenReturn(Optional.of(policy));
            TaskAgentRuntimeSession session = mock(TaskAgentRuntimeSession.class);
            when(session.canInvoke()).thenReturn(true);
            when(session.purpose()).thenReturn(TaskAgentSessionPurpose.SPECIALIST);
            when(session.agentPrincipalId()).thenReturn(reviewerAgent.id());
            when(session.agentProfileId()).thenReturn(profileId);
            when(session.agentProfileVersion()).thenReturn(3L);
            when(sessions.findByExecution(organizationId, executionId))
                    .thenReturn(List.of(session));

            service = new ReviewerExecutionApplicationService(
                    accessPolicy, tasks, executions, requests, contexts, policies,
                    principals, profiles, memberships, assignments, sessions, runtime,
                    recorder, events, queries, receipts, transactions, () -> NOW);
        }

        private TeamCommandContext commandContext() {
            return new TeamCommandContext(
                    new TeamAccessContext(actor, false),
                    io.crewscope.application.command.IdempotencyKey.from("m5-a05-reviewer-test"),
                    UUID.randomUUID(), Optional.empty());
        }

        private CommandReceipt receipt() {
            return new CommandReceipt(
                    UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        }
    }
}
