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
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
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
import io.crewscope.domain.responsibility.ReviewerEligibilityDecision;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ContextPackageReference;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** M5-A05 human Gate replay keeps current member, assignment and eligibility checks. */
class ReviewGateApplicationServiceM5A05Test {

    @Test
    void returnsReceiptReplayOnlyAfterCurrentEligibilityIsRechecked() {
        Fixture fixture = new Fixture(true);
        CommandReceipt receipt = fixture.receipt();
        when(fixture.receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(receipt));

        var result = fixture.service.record(
                fixture.context(), fixture.teamId, fixture.taskId, fixture.executionId,
                fixture.requestId, 2,
                new RecordReviewDecisionCommand(ReviewDecisionType.APPROVED, "Ready"));

        assertTrue(result.replayed());
        verify(fixture.policy).evaluateGate(
                fixture.item, fixture.actor, fixture.member,
                List.of(fixture.member), List.of(fixture.assignment));
        verify(fixture.decisions, never()).insert(any());
    }

    @Test
    void refusesReplayAfterReviewerAssignmentWasReleased() {
        Fixture fixture = new Fixture(false);
        when(fixture.receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(fixture.receipt()));

        assertThrows(DomainValidationException.class, () -> fixture.service.record(
                fixture.context(), fixture.teamId, fixture.taskId, fixture.executionId,
                fixture.requestId, 2,
                new RecordReviewDecisionCommand(ReviewDecisionType.APPROVED, "Ready")));
        verify(fixture.decisions, never()).insert(any());
    }

    private static final class Fixture {
        private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T17:00:00Z");

        private final OrganizationId organizationId = OrganizationId.generate();
        private final TeamId teamId = TeamId.generate();
        private final WorkItemScope scope = new WorkItemScope(
                organizationId, teamId, WorkspaceId.generate(), WorkProjectId.generate());
        private final WorkItemId workItemId = WorkItemId.generate();
        private final TaskId taskId = TaskId.generate();
        private final TaskExecutionId executionId = TaskExecutionId.generate();
        private final ReviewRequestId requestId = ReviewRequestId.generate();
        private final Principal actor = Principal.create(
                PrincipalId.generate(), PrincipalScope.team(organizationId, teamId),
                PrincipalType.USER, Optional.empty(), "Gate reviewer", Optional.empty(),
                PrincipalVisibility.TEAM, NOW);
        private final TeamMember member = mock(TeamMember.class);
        private final ResponsibilityAssignment assignment = mock(ResponsibilityAssignment.class);
        private final WorkItem item = mock(WorkItem.class);
        private final ReviewerEligibilityPolicy policy = mock(ReviewerEligibilityPolicy.class);
        private final ReviewDecisionRepository decisions = mock(ReviewDecisionRepository.class);
        private final CommandReceiptStore receipts = mock(CommandReceiptStore.class);
        private final ReviewGateApplicationService service;

        private Fixture(boolean assigned) {
            WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
            TaskRepository tasks = mock(TaskRepository.class);
            TaskExecutionRepository executions = mock(TaskExecutionRepository.class);
            TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
            ResponsibilityAssignmentRepository assignments =
                    mock(ResponsibilityAssignmentRepository.class);
            GateReviewerPolicyProvider policies = mock(GateReviewerPolicyProvider.class);
            ReviewRequestRepository requests = mock(ReviewRequestRepository.class);
            ContextPackageRepository contexts = mock(ContextPackageRepository.class);
            ReviewModificationRoundRepository rounds =
                    mock(ReviewModificationRoundRepository.class);
            ReviewQueryRepository queries = mock(ReviewQueryRepository.class);
            ReviewEventPublisher events = mock(ReviewEventPublisher.class);
            TransactionExecutor transactions = new TransactionExecutor() {
                @Override
                public <T> T required(Supplier<T> operation) {
                    return operation.get();
                }
            };

            Task task = mock(Task.class);
            when(task.id()).thenReturn(taskId);
            when(task.scope()).thenReturn(scope);
            when(task.workItemId()).thenReturn(workItemId);
            when(task.currentExecutionId()).thenReturn(Optional.of(executionId));
            when(tasks.findById(organizationId, taskId)).thenReturn(Optional.of(task));
            when(accessPolicy.requireVisibleWorkItem(
                    any(), any(), any(), any(), any())).thenReturn(item);
            TaskExecution execution = mock(TaskExecution.class);
            when(execution.id()).thenReturn(executionId);
            when(execution.taskId()).thenReturn(taskId);
            when(execution.scope()).thenReturn(scope);
            when(execution.attempt()).thenReturn(1);
            when(executions.findById(organizationId, executionId))
                    .thenReturn(Optional.of(execution));
            ReviewRequest request = mock(ReviewRequest.class);
            when(request.id()).thenReturn(requestId);
            when(request.taskId()).thenReturn(taskId);
            when(request.taskExecutionId()).thenReturn(executionId);
            when(request.attempt()).thenReturn(1);
            ContextPackageReference reference = mock(ContextPackageReference.class);
            ContextPackageId contextId = ContextPackageId.generate();
            when(reference.id()).thenReturn(contextId);
            when(request.contextPackage()).thenReturn(reference);
            when(requests.findById(organizationId, requestId)).thenReturn(Optional.of(request));
            when(contexts.findById(organizationId, contextId))
                    .thenReturn(Optional.of(mock(ContextPackage.class)));
            TeamMemberId memberId = TeamMemberId.generate();
            when(member.id()).thenReturn(memberId);
            when(member.userPrincipalId()).thenReturn(actor.id());
            when(member.canParticipate()).thenReturn(true);
            when(memberships.findByTeam(organizationId, teamId))
                    .thenReturn(List.of(member));
            when(assignment.isActive()).thenReturn(assigned);
            when(assignment.role()).thenReturn(ResponsibilityRole.REVIEWER);
            when(assignment.actorPrincipalId()).thenReturn(actor.id());
            when(assignment.actorMemberId()).thenReturn(Optional.of(memberId));
            when(assignments.findActiveByWorkItem(organizationId, workItemId))
                    .thenReturn(List.of(assignment));
            when(policies.resolve(item)).thenReturn(policy);
            when(policy.evaluateGate(item, actor, member, List.of(member), List.of(assignment)))
                    .thenReturn(mock(ReviewerEligibilityDecision.class));

            service = new ReviewGateApplicationService(
                    accessPolicy, tasks, executions, memberships, assignments, policies,
                    requests, contexts, decisions, rounds, queries, events, receipts,
                    transactions, () -> NOW);
        }

        private TeamCommandContext context() {
            return new TeamCommandContext(
                    new TeamAccessContext(actor, false),
                    io.crewscope.application.command.IdempotencyKey.from("m5-a05-gate-test"),
                    UUID.randomUUID(), Optional.empty());
        }

        private CommandReceipt receipt() {
            return new CommandReceipt(
                    UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        }
    }
}
