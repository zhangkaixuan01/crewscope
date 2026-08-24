package io.crewscope.application.review;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Starts/resumes one Reviewer call and atomically commits validated findings and completion. */
public final class ReviewerExecutionApplicationService {

    private static final String EXECUTE = "EXECUTE_REVIEWER";

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final ReviewRequestRepository requests;
    private final ContextPackageRepository contexts;
    private final PolicySnapshotRepository policies;
    private final PrincipalRepository principals;
    private final AgentProfileRepository profiles;
    private final TeamMembershipQuery memberships;
    private final ResponsibilityAssignmentRepository assignments;
    private final TaskAgentRuntimeSessionRepository sessions;
    private final ReviewerExecutionPort runtime;
    private final ReviewFindingBatchRecorder recorder;
    private final ReviewEventPublisher events;
    private final ReviewQueryRepository queries;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ReviewerExecutionApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            ReviewRequestRepository requests,
            ContextPackageRepository contexts,
            PolicySnapshotRepository policies,
            PrincipalRepository principals,
            AgentProfileRepository profiles,
            TeamMembershipQuery memberships,
            ResponsibilityAssignmentRepository assignments,
            TaskAgentRuntimeSessionRepository sessions,
            ReviewerExecutionPort runtime,
            ReviewFindingBatchRecorder recorder,
            ReviewEventPublisher events,
            ReviewQueryRepository queries,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.events = Objects.requireNonNull(events, "events");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CompletionStage<ReviewerExecutionResult> execute(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ReviewRequestId reviewRequestId,
            long expectedRequestVersion) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CommandRequestHash hash = CommandRequestHash.sha256(
                EXECUTE,
                trusted.access().actor().id().toString(),
                teamId.toString(),
                taskId.toString(),
                executionId.toString(),
                reviewRequestId.toString(),
                Long.toString(expectedRequestVersion));
        Accepted accepted = transactions.required(() -> accept(
                trusted, teamId, taskId, executionId, reviewRequestId,
                expectedRequestVersion, hash));
        if (accepted.replayed()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new ReviewerExecutionResult(
                            accepted.request(), Optional.empty(), accepted.receipt(), true));
        }
        return runtime.execute(accepted.command()).thenApply(candidates ->
                transactions.required(() -> complete(accepted, candidates)));
    }

    private Accepted accept(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ReviewRequestId reviewRequestId,
            long expectedRequestVersion,
            CommandRequestHash hash) {
        OrganizationId organizationId = context.access().actor().scope().organizationId();
        Task task = tasks.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        accessPolicy.requireVisibleWorkItem(
                context.access(), organizationId, teamId,
                task.scope().projectId(), task.workItemId());
        TaskExecution execution = executions.findById(organizationId, executionId)
                .filter(value -> value.taskId().equals(task.id()))
                .filter(value -> value.scope().equals(task.scope()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", executionId));
        if (task.currentExecutionId().filter(execution.id()::equals).isEmpty()) {
            throw new DomainValidationException(
                    "reviewRequest.taskExecutionId", "must be the current Task attempt");
        }
        ReviewRequest current = requests.findById(organizationId, reviewRequestId)
                .filter(value -> value.taskId().equals(task.id()))
                .filter(value -> value.taskExecutionId().equals(execution.id()))
                .filter(value -> value.attempt() == execution.attempt())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ReviewRequest", reviewRequestId));
        ContextPackage packageValue = contexts.findById(
                        organizationId, current.contextPackage().id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ContextPackage", current.contextPackage().id()));
        current.requireCurrent(packageValue);
        Principal reviewerAgent = principals.findById(
                        organizationId, current.reviewer().agentPrincipalId())
                .filter(Principal::canAct)
                .filter(value -> value.type().isAgent())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", current.reviewer().agentPrincipalId()));
        requireCurrentReviewerAuthority(task, current, reviewerAgent);
        PolicySnapshot policy = policies.findById(
                        organizationId, current.reviewer().policySnapshotId())
                .filter(value -> value.revision() == current.reviewer().policySnapshotRevision())
                .filter(value -> value.snapshotHash().equals(
                        current.reviewer().policySnapshotHash()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "PolicySnapshot", current.reviewer().policySnapshotId()));
        TaskAgentRuntimeSession session = sessions.findByExecution(
                        organizationId, execution.id()).stream()
                .filter(TaskAgentRuntimeSession::canInvoke)
                .filter(value -> value.purpose() == TaskAgentSessionPurpose.SPECIALIST)
                .filter(value -> value.agentPrincipalId().equals(reviewerAgent.id()))
                .filter(value -> value.agentProfileId().equals(
                        current.reviewer().agentProfileId()))
                .filter(value -> value.agentProfileVersion()
                        == current.reviewer().agentProfileVersion())
                .findFirst()
                .orElseThrow(() -> new DomainValidationException(
                        "reviewRequest.reviewerSession",
                        "the exact active Reviewer Specialist Session is required"));

        Optional<CommandReceipt> replay = receipts.findCompleted(
                organizationId, context.idempotencyKey(), EXECUTE, hash);
        if (replay.isPresent()) {
            return new Accepted(
                    current, packageValue, policy, session, reviewerAgent,
                    replay.orElseThrow(), true, null);
        }
        if (current.version() != expectedRequestVersion) {
            throw new OptimisticLockConflictException(
                    "ReviewRequest", current.id(), expectedRequestVersion, current.version());
        }
        UtcTimestamp now = timeProvider.now();
        ReviewRequest acceptedRequest;
        UUID eventId;
        if (current.status() == ReviewRequestStatus.OPEN) {
            acceptedRequest = current.start(
                    packageValue, expectedRequestVersion, context.access().actor(), now);
            requests.update(acceptedRequest, expectedRequestVersion);
            eventId = events.requestStarted(
                    acceptedRequest,
                    EventActor.principal(EventActorType.USER, context.access().actor().id()),
                    context.correlationId());
        } else if (current.status() == ReviewRequestStatus.IN_PROGRESS) {
            acceptedRequest = current;
            eventId = stableStartedEventId(current.id(), current.version());
        } else {
            throw new DomainValidationException(
                    "reviewRequest.status", "only OPEN or IN_PROGRESS requests can execute");
        }
        UUID commandId = UUID.randomUUID();
        CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                organizationId, context.idempotencyKey(), EXECUTE, hash,
                commandId, context.correlationId(), now));
        if (!reservation.acquired()) {
            return new Accepted(
                    acceptedRequest, packageValue, policy, session, reviewerAgent,
                    reservation.receipt().orElseThrow(), true, null);
        }
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, acceptedRequest.version(), context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, now);
        ReviewerExecutionCommand runtimeCommand = new ReviewerExecutionCommand(
                acceptedRequest, packageValue, policy, session, reviewerAgent,
                context.correlationId(), now);
        return new Accepted(
                acceptedRequest, packageValue, policy, session, reviewerAgent,
                receipt, false, runtimeCommand);
    }

    private ReviewerExecutionResult complete(
            Accepted accepted,
            List<io.crewscope.domain.review.ReviewFindingCandidate> candidates) {
        OrganizationId organizationId = accepted.request().scope().organizationId();
        ReviewRequest current = requests.findById(organizationId, accepted.request().id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ReviewRequest", accepted.request().id()));
        ContextPackage currentContext = contexts.findById(
                        organizationId, current.contextPackage().id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ContextPackage", current.contextPackage().id()));
        ReviewFindingBatchResult result = recorder.record(
                current, currentContext, candidates, accepted.request().version(),
                accepted.reviewerAgent(), timeProvider.now());
        EventActor actor = EventActor.principal(
                EventActorType.SPECIALIST_AGENT, accepted.reviewerAgent().id());
        result.insertedFindings().forEach(value ->
                events.findingRecorded(value, actor, accepted.command().correlationId()));
        result.duplicateObservations().forEach(value ->
                events.duplicateObserved(value, actor, accepted.command().correlationId()));
        UtcTimestamp completedAt = timeProvider.now();
        ReviewRequest completed = current.complete(
                currentContext, accepted.request().version(),
                accepted.reviewerAgent(), completedAt);
        requests.update(completed, accepted.request().version());
        events.requestCompleted(
                completed, actor, accepted.command().correlationId());
        queries.rebuild(organizationId, completed.id());
        return new ReviewerExecutionResult(
                completed, Optional.of(result), accepted.receipt(), false);
    }

    private void requireCurrentReviewerAuthority(
            Task task, ReviewRequest request, Principal reviewerAgent) {
        profiles.findById(task.scope().organizationId(), request.reviewer().agentProfileId())
                .filter(value -> value.version() == request.reviewer().agentProfileVersion())
                .filter(value -> value.agentPrincipalId().equals(reviewerAgent.id()))
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentProfile", request.reviewer().agentProfileId()));
        boolean assigned = assignments.findActiveByWorkItem(
                        task.scope().organizationId(), task.workItemId()).stream()
                .anyMatch(value -> value.role() == ResponsibilityRole.REVIEWER
                        && value.actorPrincipalId().equals(reviewerAgent.id()));
        if (!assigned) {
            throw new DomainValidationException(
                    "reviewRequest.reviewerAgent", "Reviewer responsibility is no longer active");
        }
        request.reviewer().reviewerOwnerMemberId().ifPresent(owner -> {
            boolean active = memberships.findByTeam(
                            task.scope().organizationId(), task.scope().teamId()).stream()
                    .anyMatch(value -> value.id().equals(owner) && value.canParticipate());
            if (!active) {
                throw new DomainValidationException(
                        "reviewRequest.reviewerOwnerMemberId",
                        "Reviewer Agent owner is no longer an active Team member");
            }
        });
    }

    private static UUID stableStartedEventId(ReviewRequestId id, long version) {
        String source = "crewscope:review:REVIEW_REQUEST_STARTED:" + id + ':' + version;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private record Accepted(
            ReviewRequest request,
            ContextPackage context,
            PolicySnapshot policy,
            TaskAgentRuntimeSession session,
            Principal reviewerAgent,
            CommandReceipt receipt,
            boolean replayed,
            ReviewerExecutionCommand command) {}
}
