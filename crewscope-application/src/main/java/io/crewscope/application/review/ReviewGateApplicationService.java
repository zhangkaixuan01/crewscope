package io.crewscope.application.review;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewDecision;
import io.crewscope.domain.review.ReviewDecisionId;
import io.crewscope.domain.review.ReviewDecisionType;
import io.crewscope.domain.review.ReviewModificationRound;
import io.crewscope.domain.review.ReviewModificationRoundId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workitem.WorkItem;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Records eligible human Gate decisions and continuous modification rounds. */
public final class ReviewGateApplicationService {

    private static final String RECORD_DECISION = "RECORD_REVIEW_GATE_DECISION";

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final TeamMembershipQuery memberships;
    private final ResponsibilityAssignmentRepository assignments;
    private final GateReviewerPolicyProvider policies;
    private final ReviewRequestRepository requests;
    private final ContextPackageRepository contexts;
    private final ReviewDecisionRepository decisions;
    private final ReviewModificationRoundRepository rounds;
    private final ReviewQueryRepository queries;
    private final ReviewEventPublisher events;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ReviewGateApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            TeamMembershipQuery memberships,
            ResponsibilityAssignmentRepository assignments,
            GateReviewerPolicyProvider policies,
            ReviewRequestRepository requests,
            ContextPackageRepository contexts,
            ReviewDecisionRepository decisions,
            ReviewModificationRoundRepository rounds,
            ReviewQueryRepository queries,
            ReviewEventPublisher events,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.rounds = Objects.requireNonNull(rounds, "rounds");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.events = Objects.requireNonNull(events, "events");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<ReviewDecision> record(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ReviewRequestId reviewRequestId,
            long expectedRequestVersion,
            RecordReviewDecisionCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        RecordReviewDecisionCommand required = Objects.requireNonNull(command, "command");
        CommandRequestHash hash = CommandRequestHash.sha256(
                RECORD_DECISION,
                trusted.access().actor().id().toString(),
                teamId.toString(),
                taskId.toString(),
                executionId.toString(),
                reviewRequestId.toString(),
                Long.toString(expectedRequestVersion),
                required.type().name(),
                required.rationale());
        return transactions.required(() -> {
            OrganizationId organizationId = trusted.access().actor().scope().organizationId();
            Task task = tasks.findById(organizationId, taskId)
                    .filter(value -> value.scope().teamId().equals(teamId))
                    .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
            WorkItem item = accessPolicy.requireVisibleWorkItem(
                    trusted.access(), organizationId, teamId,
                    task.scope().projectId(), task.workItemId());
            TaskExecution execution = executions.findById(organizationId, executionId)
                    .filter(value -> value.taskId().equals(task.id()))
                    .filter(value -> value.scope().equals(task.scope()))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TaskExecution", executionId));
            if (task.currentExecutionId().filter(execution.id()::equals).isEmpty()) {
                throw new io.crewscope.domain.shared.error.DomainValidationException(
                        "reviewDecision.reviewRequest",
                        "must belong to the current Task attempt");
            }
            ReviewRequest request = requests.findById(organizationId, reviewRequestId)
                    .filter(value -> value.taskId().equals(task.id()))
                    .filter(value -> value.taskExecutionId().equals(execution.id()))
                    .filter(value -> value.attempt() == execution.attempt())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "ReviewRequest", reviewRequestId));
            ContextPackage packageValue = contexts.findById(
                            organizationId, request.contextPackage().id())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "ContextPackage", request.contextPackage().id()));
            List<TeamMember> teamMembers = memberships.findByTeam(organizationId, teamId);
            TeamMember reviewerMember = teamMembers.stream()
                    .filter(TeamMember::canParticipate)
                    .filter(value -> value.userPrincipalId().equals(
                            trusted.access().actor().id()))
                    .findFirst()
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TeamMember", trusted.access().actor().id()));
            var currentAssignments = assignments.findActiveByWorkItem(
                    organizationId, task.workItemId());
            // Receipt replay never bypasses current Reviewer assignment or separation policy.
            policies.resolve(item).evaluateGate(
                    item, trusted.access().actor(), reviewerMember,
                    teamMembers, currentAssignments);
            boolean currentlyAssigned = currentAssignments.stream().anyMatch(value ->
                    value.isActive()
                            && value.role()
                            == io.crewscope.domain.responsibility.ResponsibilityRole.REVIEWER
                            && value.actorPrincipalId().equals(trusted.access().actor().id())
                            && value.actorMemberId().filter(reviewerMember.id()::equals).isPresent());
            if (!currentlyAssigned) {
                throw new io.crewscope.domain.shared.error.DomainValidationException(
                        "reviewDecision.reviewerMemberId",
                        "must hold the current active USER Reviewer assignment");
            }
            var replay = receipts.findCompleted(
                    organizationId, trusted.idempotencyKey(), RECORD_DECISION, hash);
            if (replay.isPresent()) {
                return CommandExecution.replayed(replay.orElseThrow());
            }
            // Evaluate current membership, assignment and separation before first execution.
            ReviewDecision candidate = decision(
                    organizationId, request, packageValue, task, item, required,
                    expectedRequestVersion, trusted.access().actor(), reviewerMember, teamMembers,
                    currentAssignments, timeProvider.now());
            UtcTimestamp now = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId, trusted.idempotencyKey(), RECORD_DECISION, hash,
                    commandId, trusted.correlationId(), now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            decisions.insert(candidate);
            UUID eventId = events.decisionRecorded(
                    candidate,
                    EventActor.principal(EventActorType.USER, trusted.access().actor().id()),
                    trusted.correlationId());
            if (candidate.type() == ReviewDecisionType.CHANGES_REQUESTED) {
                ReviewModificationRound round = rounds.findLatestByTask(
                                organizationId, task.id())
                        .map(previous -> ReviewModificationRound.successor(
                                ReviewModificationRoundId.generate(), previous, candidate,
                                trusted.access().actor(), now))
                        .orElseGet(() -> ReviewModificationRound.initial(
                                ReviewModificationRoundId.generate(), candidate,
                                trusted.access().actor(), now));
                rounds.insert(round);
                events.modificationRoundStarted(
                        round,
                        EventActor.principal(
                                EventActorType.USER, trusted.access().actor().id()),
                        trusted.correlationId());
            }
            queries.rebuild(organizationId, request.id());
            CommandReceipt receipt = new CommandReceipt(
                    commandId, eventId, candidate.revision(), trusted.correlationId());
            receipts.complete(organizationId, trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(candidate, receipt);
        });
    }

    private ReviewDecision decision(
            OrganizationId organizationId,
            ReviewRequest request,
            ContextPackage context,
            Task task,
            WorkItem item,
            RecordReviewDecisionCommand command,
            long expectedRequestVersion,
            io.crewscope.domain.identity.Principal reviewer,
            TeamMember reviewerMember,
            List<TeamMember> teamMembers,
            List<io.crewscope.domain.responsibility.ResponsibilityAssignment> currentAssignments,
            UtcTimestamp now) {
        return decisions.findLatestByRequest(organizationId, request.id())
                .map(previous -> ReviewDecision.successor(
                        ReviewDecisionId.generate(), previous, request, context, task, item,
                        command.type(), command.rationale(), expectedRequestVersion,
                        policies.resolve(item),
                        reviewer,
                        reviewerMember, teamMembers, currentAssignments, now))
                .orElseGet(() -> ReviewDecision.initial(
                        ReviewDecisionId.generate(), request, context, task, item,
                        command.type(), command.rationale(), expectedRequestVersion,
                        policies.resolve(item), reviewer,
                        reviewerMember, teamMembers, currentAssignments, now));
    }
}
