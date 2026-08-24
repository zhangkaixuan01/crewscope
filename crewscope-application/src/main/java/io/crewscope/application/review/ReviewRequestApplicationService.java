package io.crewscope.application.review;

import io.crewscope.application.artifact.ArtifactAccessContext;
import io.crewscope.application.coding.CommandEvidenceRepository;
import io.crewscope.application.coding.DiffArtifactRepository;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.task.PolicySnapshotRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.task.TaskRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.coding.CommandEvidence;
import io.crewscope.domain.coding.DiffArtifact;
import io.crewscope.domain.coding.TestEvidence;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.review.ContextPackage;
import io.crewscope.domain.review.ContextPackageId;
import io.crewscope.domain.review.ReviewRequest;
import io.crewscope.domain.review.ReviewRequestId;
import io.crewscope.domain.review.ReviewRequestStatus;
import io.crewscope.domain.review.ReviewSubject;
import io.crewscope.domain.review.ReviewSubjectId;
import io.crewscope.domain.review.ReviewerExecutionReference;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workitem.WorkItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates and queries ReviewRequests from exact Task, Artifact and Reviewer policy facts. */
public final class ReviewRequestApplicationService {

    private static final String CREATE = "CREATE_REVIEW_REQUEST";
    private static final String RE_REVIEW = "CREATE_RE_REVIEW_REQUEST";

    private final WorkItemAccessPolicy accessPolicy;
    private final TaskRepository tasks;
    private final TaskExecutionRepository executions;
    private final DiffArtifactRepository diffs;
    private final TestEvidenceRepository tests;
    private final CommandEvidenceRepository commands;
    private final PolicySnapshotRepository policies;
    private final PrincipalRepository principals;
    private final AgentProfileRepository profiles;
    private final TeamMembershipQuery memberships;
    private final ResponsibilityAssignmentRepository assignments;
    private final ReviewSubjectRepository subjects;
    private final ContextPackageRepository contexts;
    private final ReviewRequestRepository requests;
    private final ReviewFindingRepository findings;
    private final ReviewDecisionRepository decisions;
    private final ReviewModificationRoundRepository rounds;
    private final ReviewQueryRepository queries;
    private final ContextPackageBuilder contextBuilder;
    private final ReviewEventPublisher events;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public ReviewRequestApplicationService(
            WorkItemAccessPolicy accessPolicy,
            TaskRepository tasks,
            TaskExecutionRepository executions,
            DiffArtifactRepository diffs,
            TestEvidenceRepository tests,
            CommandEvidenceRepository commands,
            PolicySnapshotRepository policies,
            PrincipalRepository principals,
            AgentProfileRepository profiles,
            TeamMembershipQuery memberships,
            ResponsibilityAssignmentRepository assignments,
            ReviewSubjectRepository subjects,
            ContextPackageRepository contexts,
            ReviewRequestRepository requests,
            ReviewFindingRepository findings,
            ReviewDecisionRepository decisions,
            ReviewModificationRoundRepository rounds,
            ReviewQueryRepository queries,
            ContextPackageBuilder contextBuilder,
            ReviewEventPublisher events,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.diffs = Objects.requireNonNull(diffs, "diffs");
        this.tests = Objects.requireNonNull(tests, "tests");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.subjects = Objects.requireNonNull(subjects, "subjects");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.findings = Objects.requireNonNull(findings, "findings");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.rounds = Objects.requireNonNull(rounds, "rounds");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.events = Objects.requireNonNull(events, "events");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public CommandExecution<ReviewRequest> create(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            CreateReviewRequestCommand command) {
        return createInternal(
                context, teamId, taskId, executionId, Optional.empty(), command, false);
    }

    public CommandExecution<ReviewRequest> reReview(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ReviewRequestId predecessorRequestId,
            CreateReviewRequestCommand command) {
        return createInternal(
                context, teamId, taskId, executionId,
                Optional.of(Objects.requireNonNull(
                        predecessorRequestId, "predecessorRequestId")), command, true);
    }

    private CommandExecution<ReviewRequest> createInternal(
            TeamCommandContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            Optional<ReviewRequestId> expectedPredecessor,
            CreateReviewRequestCommand command,
            boolean successor) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CreateReviewRequestCommand required = Objects.requireNonNull(command, "command");
        String commandType = successor ? RE_REVIEW : CREATE;
        CommandRequestHash hash = CommandRequestHash.sha256(
                commandType,
                trusted.access().actor().id().toString(),
                teamId.toString(),
                taskId.toString(),
                executionId.toString(),
                expectedPredecessor.map(Object::toString).orElse("initial"),
                required.reviewerPolicySnapshotId().toString());
        return transactions.required(() -> {
            CreationFacts facts = creationFacts(
                    trusted.access(), teamId, taskId, executionId,
                    required.reviewerPolicySnapshotId());
            Optional<CommandReceipt> replay = receipts.findCompleted(
                    facts.task().scope().organizationId(), trusted.idempotencyKey(),
                    commandType, hash);
            if (replay.isPresent()) {
                return CommandExecution.replayed(replay.orElseThrow());
            }
            Optional<ReviewRequest> current = requests.findCurrentByExecution(
                    facts.task().scope().organizationId(), executionId, facts.execution().attempt());
            requireCreationMode(current, successor);
            if (successor && current.map(ReviewRequest::id)
                    .filter(expectedPredecessor.orElseThrow()::equals).isEmpty()) {
                throw new AggregateNotFoundException(
                        "ReviewRequest", expectedPredecessor.orElseThrow());
            }
            UtcTimestamp now = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    facts.task().scope().organizationId(), trusted.idempotencyKey(), commandType,
                    hash, commandId, trusted.correlationId(), now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }

            ReviewSubject subject = ReviewSubject.codeChange(
                    ReviewSubjectId.generate(), facts.task().scope(), taskId, executionId,
                    facts.execution().attempt(),
                    io.crewscope.domain.review.ReviewDiffReference.from(facts.diff()),
                    trusted.access().actor(), now);
            Optional<ContextPackage> parentContext = successor
                    ? contexts.findLatestByExecution(
                            facts.task().scope().organizationId(), executionId,
                            facts.execution().attempt())
                    : Optional.empty();
            ContextPackage packageValue = contextBuilder.build(new BuildReviewContextPackageRequest(
                    ContextPackageId.generate(),
                    parentContext,
                    subject,
                    facts.diff(),
                    facts.testEvidence(),
                    facts.commandEvidence(),
                    facts.reviewer(),
                    new ArtifactAccessContext(
                            facts.task().scope().organizationId(),
                            trusted.access().actor().id(),
                            java.util.Set.of(teamId),
                            java.util.Set.of(facts.task().scope().workspaceId())),
                    trusted.access().actor(),
                    now));
            ReviewRequest request = current
                    .map(value -> ReviewRequest.successor(
                            ReviewRequestId.generate(), value, packageValue,
                            trusted.access().actor(), now))
                    .orElseGet(() -> ReviewRequest.initial(
                            ReviewRequestId.generate(), packageValue,
                            trusted.access().actor(), now));
            subjects.save(subject);
            contexts.save(packageValue);
            requests.insert(request);
            queries.rebuild(facts.task().scope().organizationId(), request.id());
            UUID eventId = events.requestCreated(
                    request, EventActor.principal(EventActorType.USER,
                            trusted.access().actor().id()), trusted.correlationId());
            CommandReceipt receipt = new CommandReceipt(
                    commandId, eventId, request.version(), trusted.correlationId());
            receipts.complete(
                    facts.task().scope().organizationId(), trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(request, receipt);
        });
    }

    public List<ReviewRequestProjection> list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId) {
        return transactions.required(() -> {
            Task task = requireTask(context, organizationId, teamId, taskId);
            TaskExecution execution = requireExecution(organizationId, task, executionId);
            return queries.findByExecution(organizationId, executionId, execution.attempt()).stream()
                    .filter(value -> value.scope().equals(task.scope()))
                    .toList();
        });
    }

    public ReviewWorkbenchView get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            ReviewRequestId reviewRequestId) {
        return transactions.required(() -> {
            Task task = requireTask(context, organizationId, teamId, taskId);
            TaskExecution execution = requireExecution(organizationId, task, executionId);
            ReviewRequest request = requests.findById(organizationId, reviewRequestId)
                    .filter(value -> belongsTo(value, task, execution))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "ReviewRequest", reviewRequestId));
            ContextPackage packageValue = contexts.findById(
                            organizationId, request.contextPackage().id())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "ContextPackage", request.contextPackage().id()));
            return new ReviewWorkbenchView(
                    request,
                    packageValue,
                    findings.findAllByRequest(organizationId, request.id()),
                    decisions.findDecisionsByRequest(organizationId, request.id()),
                    rounds.findAllByTask(organizationId, task.id()));
        });
    }

    private CreationFacts creationFacts(
            TeamAccessContext context,
            TeamId teamId,
            TaskId taskId,
            TaskExecutionId executionId,
            io.crewscope.domain.task.PolicySnapshotId policySnapshotId) {
        OrganizationId organizationId = context.actor().scope().organizationId();
        Task task = requireTask(context, organizationId, teamId, taskId);
        TaskExecution execution = requireExecution(organizationId, task, executionId);
        if (task.currentExecutionId().filter(executionId::equals).isEmpty()) {
            throw new DomainValidationException(
                    "reviewRequest.taskExecutionId", "must be the current Task attempt");
        }
        DiffArtifact diff = diffs.findByTaskExecution(
                        organizationId, teamId, task.scope().projectId(), executionId)
                .orElseThrow(() -> new AggregateNotFoundException("DiffArtifact", executionId));
        TestEvidence test = tests.findByTaskExecution(
                        organizationId, teamId, task.scope().projectId(), executionId).stream()
                .filter(value -> value.attempt() == execution.attempt())
                .filter(value -> value.codingTarget().equals(diff.codingTarget()))
                .filter(value -> value.diffGeneration().equals(diff.manifest().generation()))
                .filter(value -> value.diffManifestHash().equals(diff.manifest().contentHash()))
                .reduce((left, right) -> right)
                .orElseThrow(() -> new DomainValidationException(
                        "reviewRequest.testEvidence", "current Diff has no exact TestEvidence"));
        List<CommandEvidence> commandEvidence = exactCommands(task, execution, test);
        PolicySnapshot policy = policies.findById(organizationId, policySnapshotId)
                .filter(value -> value.scope().equals(task.scope()))
                .filter(value -> value.taskId().equals(task.id()))
                .filter(value -> value.executionId().equals(execution.id()))
                .orElseThrow(() -> new AggregateNotFoundException("PolicySnapshot", policySnapshotId));
        var resolved = policy.agentExecutionConfiguration().orElseThrow(() ->
                new DomainValidationException(
                        "reviewRequest.reviewerPolicySnapshotId", "must use Schema v2"));
        if (!resolved.templateVersion().key().equals(new AgentTemplateKey("reviewer"))) {
            throw new DomainValidationException(
                    "reviewRequest.reviewerPolicySnapshotId", "must select a Reviewer Agent");
        }
        Principal reviewerAgent = principals.findById(organizationId, resolved.agentPrincipalId())
                .filter(Principal::canAct)
                .filter(value -> value.type().isAgent())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", resolved.agentPrincipalId()));
        var profile = profiles.findById(organizationId, resolved.agentProfileId())
                .filter(value -> value.version() == resolved.agentProfileVersion())
                .filter(value -> value.agentPrincipalId().equals(reviewerAgent.id()))
                .filter(value -> value.status() == AgentProfileStatus.ACTIVE)
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentProfile", resolved.agentProfileId()));
        resolved.ownership().ownerMemberId().ifPresent(ownerMemberId -> {
            boolean ownerActive = memberships.findByTeam(organizationId, teamId).stream()
                    .anyMatch(value -> value.id().equals(ownerMemberId)
                            && value.canParticipate());
            if (!ownerActive) {
                throw new DomainValidationException(
                        "reviewRequest.reviewerOwnerMemberId",
                        "Reviewer Agent owner must be an active Team member");
            }
        });
        List<ResponsibilityAssignment> currentAssignments = assignments.findActiveByWorkItem(
                organizationId, task.workItemId());
        boolean assignedReviewer = currentAssignments.stream().anyMatch(value ->
                value.isActive()
                        && value.role() == ResponsibilityRole.REVIEWER
                        && value.actorPrincipalId().equals(reviewerAgent.id()));
        if (!assignedReviewer) {
            throw new DomainValidationException(
                    "reviewRequest.reviewerAgent", "must hold the active advisory Reviewer responsibility");
        }
        List<TeamMemberId> subjectOwners = currentAssignments.stream()
                .filter(ResponsibilityAssignment::isActive)
                .filter(value -> value.role() == ResponsibilityRole.OWNER)
                .map(ResponsibilityAssignment::actorMemberId)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        if (subjectOwners.size() != 1) {
            throw new DomainValidationException(
                    "reviewRequest.subjectOwnerMemberId",
                    "must resolve exactly one active member Owner");
        }
        ReviewerExecutionReference reviewer = ReviewerExecutionReference.capture(
                policy, Optional.of(subjectOwners.get(0)));
        if (!reviewer.agentProfileId().equals(profile.id())) {
            throw new DomainValidationException(
                    "reviewRequest.reviewerAgent", "Reviewer profile coordinates changed");
        }
        return new CreationFacts(task, execution, diff, test, commandEvidence, reviewer);
    }

    private List<CommandEvidence> exactCommands(
            Task task, TaskExecution execution, TestEvidence test) {
        Map<io.crewscope.domain.coding.CommandEvidenceId, CommandEvidence> indexed =
                new LinkedHashMap<>();
        commands.findByTaskExecution(
                        task.scope().organizationId(), task.scope().teamId(),
                        task.scope().projectId(), execution.id())
                .forEach(value -> indexed.put(value.id(), value));
        return test.commands().stream()
                .map(reference -> Optional.ofNullable(indexed.get(reference.id()))
                        .filter(value -> value.reference().equals(reference))
                        .orElseThrow(() -> new DomainValidationException(
                                "reviewRequest.commandEvidence",
                                "TestEvidence references unavailable command facts")))
                .toList();
    }

    private Task requireTask(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TaskId taskId) {
        Task task = tasks.findById(organizationId, taskId)
                .filter(value -> value.scope().teamId().equals(teamId))
                .orElseThrow(() -> new AggregateNotFoundException("Task", taskId));
        WorkItem item = accessPolicy.requireVisibleWorkItem(
                context, organizationId, teamId, task.scope().projectId(), task.workItemId());
        if (!item.scope().equals(task.scope())) {
            throw new AggregateNotFoundException("Task", taskId);
        }
        return task;
    }

    private TaskExecution requireExecution(
            OrganizationId organizationId, Task task, TaskExecutionId executionId) {
        return executions.findById(organizationId, executionId)
                .filter(value -> value.taskId().equals(task.id()))
                .filter(value -> value.scope().equals(task.scope()))
                .orElseThrow(() -> new AggregateNotFoundException("TaskExecution", executionId));
    }

    private static boolean belongsTo(
            ReviewRequest request, Task task, TaskExecution execution) {
        return request.scope().equals(task.scope())
                && request.taskId().equals(task.id())
                && request.taskExecutionId().equals(execution.id())
                && request.attempt() == execution.attempt();
    }

    private static void requireCreationMode(
            Optional<ReviewRequest> current, boolean successor) {
        if (!successor && current.isPresent()) {
            throw new DomainValidationException(
                    "reviewRequest", "the execution attempt already has a ReviewRequest");
        }
        if (successor && current.filter(value ->
                value.status() == ReviewRequestStatus.INVALIDATED).isEmpty()) {
            throw new DomainValidationException(
                    "reviewRequest", "re-review requires an invalidated predecessor");
        }
    }

    private record CreationFacts(
            Task task,
            TaskExecution execution,
            DiffArtifact diff,
            TestEvidence testEvidence,
            List<CommandEvidence> commandEvidence,
            ReviewerExecutionReference reviewer) {

        private CreationFacts {
            commandEvidence = List.copyOf(commandEvidence);
        }
    }
}
