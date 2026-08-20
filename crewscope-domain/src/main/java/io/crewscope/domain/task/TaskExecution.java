package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One durable attempt to execute a Task.
 *
 * <p>The aggregate owns scheduling and operational lifecycle facts. Worker ownership, lease and
 * fencing facts are introduced separately by M3-D05.
 */
public final class TaskExecution {

    public static final int MAX_SUPPORTED_ATTEMPTS = 100;

    private static final Map<TaskExecutionStatus, Set<TaskExecutionStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(TaskExecutionStatus.CREATED, EnumSet.of(
                    TaskExecutionStatus.READY, TaskExecutionStatus.CANCEL_REQUESTED)),
            Map.entry(TaskExecutionStatus.READY, EnumSet.of(
                    TaskExecutionStatus.CLAIMED,
                    TaskExecutionStatus.WAITING,
                    TaskExecutionStatus.CANCEL_REQUESTED)),
            Map.entry(TaskExecutionStatus.CLAIMED, EnumSet.of(
                    TaskExecutionStatus.PREPARING,
                    TaskExecutionStatus.RECOVERING,
                    TaskExecutionStatus.CANCEL_REQUESTED)),
            Map.entry(TaskExecutionStatus.PREPARING, EnumSet.of(
                    TaskExecutionStatus.RUNNING,
                    TaskExecutionStatus.RECOVERING,
                    TaskExecutionStatus.CANCEL_REQUESTED)),
            Map.entry(TaskExecutionStatus.RUNNING, EnumSet.of(
                    TaskExecutionStatus.WAITING,
                    TaskExecutionStatus.PAUSE_REQUESTED,
                    TaskExecutionStatus.RECOVERING,
                    TaskExecutionStatus.CANCEL_REQUESTED,
                    TaskExecutionStatus.MANUAL_TAKEOVER,
                    TaskExecutionStatus.COMPLETED,
                    TaskExecutionStatus.FAILED)),
            Map.entry(TaskExecutionStatus.WAITING, EnumSet.of(
                    TaskExecutionStatus.READY,
                    TaskExecutionStatus.CANCEL_REQUESTED,
                    TaskExecutionStatus.MANUAL_TAKEOVER)),
            Map.entry(TaskExecutionStatus.PAUSE_REQUESTED, EnumSet.of(
                    TaskExecutionStatus.PAUSED,
                    TaskExecutionStatus.COMPLETED,
                    TaskExecutionStatus.CANCEL_REQUESTED)),
            Map.entry(TaskExecutionStatus.PAUSED, EnumSet.of(
                    TaskExecutionStatus.READY, TaskExecutionStatus.CANCEL_REQUESTED)),
            Map.entry(TaskExecutionStatus.RECOVERING, EnumSet.of(
                    TaskExecutionStatus.READY,
                    TaskExecutionStatus.CANCEL_REQUESTED,
                    TaskExecutionStatus.FAILED)),
            Map.entry(TaskExecutionStatus.CANCEL_REQUESTED, EnumSet.of(
                    TaskExecutionStatus.CANCELLED)),
            Map.entry(TaskExecutionStatus.MANUAL_TAKEOVER, EnumSet.of(
                    TaskExecutionStatus.COMPLETED,
                    TaskExecutionStatus.FAILED,
                    TaskExecutionStatus.CANCEL_REQUESTED)),
            Map.entry(TaskExecutionStatus.COMPLETED, EnumSet.noneOf(TaskExecutionStatus.class)),
            Map.entry(TaskExecutionStatus.FAILED, EnumSet.noneOf(TaskExecutionStatus.class)),
            Map.entry(TaskExecutionStatus.CANCELLED, EnumSet.noneOf(TaskExecutionStatus.class)));

    private final TaskExecutionId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final int attempt;
    private final int maxAttempts;
    private final Optional<TaskExecutionId> parentExecutionId;
    private final TaskExecutionPriority priority;
    private final UtcTimestamp notBefore;
    private final TaskExecutionStatus status;
    private final Optional<TaskExecutionWaiting> waiting;
    private final Optional<TaskExecutionControlRequest> controlRequest;
    private final Optional<TaskExecutionTerminal> terminal;
    private final Optional<TaskExecutionPlanningContext> planningContext;
    private final Optional<FencingToken> lastFencingToken;
    private final long version;
    private final AuditMetadata audit;

    private TaskExecution(
            TaskExecutionId id,
            WorkItemScope scope,
            TaskId taskId,
            int attempt,
            int maxAttempts,
            Optional<TaskExecutionId> parentExecutionId,
            TaskExecutionPriority priority,
            UtcTimestamp notBefore,
            TaskExecutionStatus status,
            Optional<TaskExecutionWaiting> waiting,
            Optional<TaskExecutionControlRequest> controlRequest,
            Optional<TaskExecutionTerminal> terminal,
            Optional<TaskExecutionPlanningContext> planningContext,
            Optional<FencingToken> lastFencingToken,
            long version,
            AuditMetadata audit) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.maxAttempts = requireMaxAttempts(maxAttempts);
        this.attempt = requireAttempt(attempt, this.maxAttempts);
        this.parentExecutionId = requireParent(id, attempt, parentExecutionId);
        this.priority = Objects.requireNonNull(priority, "priority");
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
        this.status = Objects.requireNonNull(status, "status");
        this.waiting = requireWaiting(status, waiting);
        this.controlRequest = requireControlRequest(status, controlRequest);
        this.terminal = requireTerminal(status, terminal);
        this.planningContext = Objects.requireNonNull(planningContext, "planningContext");
        this.lastFencingToken = Objects.requireNonNull(lastFencingToken, "lastFencingToken");
        if (requiresActiveOwnership(status) && this.lastFencingToken.isEmpty()) {
            throw new DomainValidationException(
                    "taskExecution.lastFencingToken",
                    "must exist while the execution is owned or recovering");
        }
        this.version = requireVersion(version);
        this.audit = Objects.requireNonNull(audit, "audit");
        requireTemporalOrder(this.notBefore, this.waiting, this.controlRequest, this.terminal, this.audit);
    }

    /** Creates the first execution attempt in CREATED before it becomes scheduler-visible. */
    public static TaskExecution firstAttempt(
            TaskExecutionId id,
            Task task,
            int maxAttempts,
            TaskExecutionPriority priority,
            UtcTimestamp notBefore,
            Principal actor,
            UtcTimestamp occurredAt) {
        Task requiredTask = requireOpenTask(task);
        if (requiredTask.status() != TaskStatus.CREATED
                || requiredTask.currentExecutionId().isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.taskId",
                    "must reference a CREATED Task without an execution attempt");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredTask.scope(), "taskExecution.createdByPrincipalId");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        UtcTimestamp requiredNotBefore = Objects.requireNonNull(notBefore, "notBefore");
        return new TaskExecution(
                id,
                requiredTask.scope(),
                requiredTask.id(),
                1,
                maxAttempts,
                Optional.empty(),
                priority,
                requiredNotBefore,
                TaskExecutionStatus.CREATED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    /** Creates the only legal successor of a retryable failed attempt. */
    public static TaskExecution retry(
            TaskExecutionId id,
            Task task,
            TaskExecution failedParent,
            TaskExecutionPriority priority,
            UtcTimestamp notBefore,
            Principal actor,
            UtcTimestamp occurredAt) {
        Task requiredTask = requireOpenTask(task);
        TaskExecution parent = Objects.requireNonNull(failedParent, "failedParent");
        if (!parent.scope.equals(requiredTask.scope()) || !parent.taskId.equals(requiredTask.id())) {
            throw new DomainValidationException(
                    "taskExecution.parentExecutionId", "must belong to the same Task and scope");
        }
        if (requiredTask.status() != TaskStatus.FAILED
                || requiredTask.currentExecutionId().filter(parent.id::equals).isEmpty()) {
            throw new DomainValidationException(
                    "taskExecution.parentExecutionId",
                    "must identify the Task current failed attempt");
        }
        TaskExecutionFailure failure = parent.terminal
                .filter(value -> value.status() == TaskExecutionStatus.FAILED)
                .flatMap(TaskExecutionTerminal::failure)
                .orElseThrow(() -> new DomainValidationException(
                        "taskExecution.parentExecutionId", "must identify a failed attempt"));
        if (!failure.isRetryable()) {
            throw new DomainValidationException(
                    "taskExecution.failureClass", "must allow retry");
        }
        if (parent.attempt >= parent.maxAttempts) {
            throw new DomainValidationException(
                    "taskExecution.attempt", "must not exceed maxAttempts");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredTask.scope(), "taskExecution.createdByPrincipalId");
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new TaskExecution(
                id,
                requiredTask.scope(),
                requiredTask.id(),
                parent.attempt + 1,
                parent.maxAttempts,
                Optional.of(parent.id),
                priority,
                notBefore,
                TaskExecutionStatus.CREATED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(actorId, requiredTime));
    }

    /** Reconstitutes a committed attempt and validates every state-dependent fact. */
    public static TaskExecution reconstitute(
            TaskExecutionId id,
            WorkItemScope scope,
            TaskId taskId,
            int attempt,
            int maxAttempts,
            Optional<TaskExecutionId> parentExecutionId,
            TaskExecutionPriority priority,
            UtcTimestamp notBefore,
            TaskExecutionStatus status,
            Optional<TaskExecutionWaiting> waiting,
            Optional<TaskExecutionControlRequest> controlRequest,
            Optional<TaskExecutionTerminal> terminal,
            Optional<TaskExecutionPlanningContext> planningContext,
            Optional<FencingToken> lastFencingToken,
            long version,
            AuditMetadata audit) {
        return new TaskExecution(
                id,
                scope,
                taskId,
                attempt,
                maxAttempts,
                parentExecutionId,
                priority,
                notBefore,
                status,
                waiting,
                controlRequest,
                terminal,
                planningContext,
                lastFencingToken,
                version,
                audit);
    }

    /** Reconstitutes pre-M3-D05 rows that do not yet carry a committed fencing epoch. */
    public static TaskExecution reconstitute(
            TaskExecutionId id,
            WorkItemScope scope,
            TaskId taskId,
            int attempt,
            int maxAttempts,
            Optional<TaskExecutionId> parentExecutionId,
            TaskExecutionPriority priority,
            UtcTimestamp notBefore,
            TaskExecutionStatus status,
            Optional<TaskExecutionWaiting> waiting,
            Optional<TaskExecutionControlRequest> controlRequest,
            Optional<TaskExecutionTerminal> terminal,
            Optional<TaskExecutionPlanningContext> planningContext,
            long version,
            AuditMetadata audit) {
        return reconstitute(
                id, scope, taskId, attempt, maxAttempts, parentExecutionId, priority, notBefore,
                status, waiting, controlRequest, terminal, planningContext, Optional.empty(),
                version, audit);
    }

    /** Backward-compatible reconstitution for executions created before planning facts exist. */
    public static TaskExecution reconstitute(
            TaskExecutionId id,
            WorkItemScope scope,
            TaskId taskId,
            int attempt,
            int maxAttempts,
            Optional<TaskExecutionId> parentExecutionId,
            TaskExecutionPriority priority,
            UtcTimestamp notBefore,
            TaskExecutionStatus status,
            Optional<TaskExecutionWaiting> waiting,
            Optional<TaskExecutionControlRequest> controlRequest,
            Optional<TaskExecutionTerminal> terminal,
            long version,
            AuditMetadata audit) {
        return reconstitute(
                id, scope, taskId, attempt, maxAttempts, parentExecutionId, priority, notBefore,
                status, waiting, controlRequest, terminal, Optional.empty(), Optional.empty(),
                version, audit);
    }

    /** Pins the initial immutable policy and unrestricted safety stream before queue publication. */
    public TaskExecution initializePlanningContext(
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != TaskExecutionStatus.CREATED || planningContext.isPresent()) {
            throw new InvalidStateTransitionException("TaskExecution", id, status, status);
        }
        requirePlanningLineage(policy, overlay);
        PrincipalId actorId = requireActor(actor);
        return copyWithPlanningContext(
                Optional.of(TaskExecutionPlanningContext.initial(policy, overlay)),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Switches to a new immutable policy revision and invalidates any current plan pointer. */
    public TaskExecution switchPolicySnapshot(
            PolicySnapshot replacement,
            PolicySnapshot expectedCurrent,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        TaskExecutionPlanningContext context = planningContext.orElseThrow(() ->
                new DomainValidationException("taskExecution.planningContext", "must be initialized"));
        PolicySnapshot current = Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        PolicySnapshot next = Objects.requireNonNull(replacement, "replacement");
        requirePolicyLineage(current);
        requirePolicyLineage(next);
        if (!context.policySnapshotId().equals(current.id())
                || !context.policySnapshotHash().equals(current.snapshotHash())
                || next.parentSnapshotId().filter(current.id()::equals).isEmpty()
                || next.revision() != current.revision() + 1) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.policySnapshotId",
                    "must switch from the current policy to its immediate successor");
        }
        PrincipalId actorId = requireActor(actor);
        return copyWithPlanningContext(
                Optional.of(context.withPolicy(next)),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Advances the current real-time safety version; older or unrelated overlays are rejected. */
    public TaskExecution tightenSafetyOverlay(
            SafetyEnforcementOverlay replacement,
            SafetyEnforcementOverlay expectedCurrent,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        TaskExecutionPlanningContext context = planningContext.orElseThrow(() ->
                new DomainValidationException("taskExecution.planningContext", "must be initialized"));
        SafetyEnforcementOverlay current = Objects.requireNonNull(expectedCurrent, "expectedCurrent");
        SafetyEnforcementOverlay next = Objects.requireNonNull(replacement, "replacement");
        requireOverlayLineage(current);
        requireOverlayLineage(next);
        if (!context.safetyOverlay().equals(current.reference())
                || !current.id().equals(next.id())
                || next.version() != current.version() + 1
                || next.parentOverlayHash().filter(current.overlayHash()::equals).isEmpty()
                || !next.restrictions().containsAll(current.restrictions())
                || !next.disabledCapabilities().containsAll(current.disabledCapabilities())
                || !next.disabledTools().containsAll(current.disabledTools())) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.safetyOverlay",
                    "must advance and tighten the exact current overlay version");
        }
        PrincipalId actorId = requireActor(actor);
        return copyWithPlanningContext(
                Optional.of(context.withOverlay(next)),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Selects a published Plan whose exact policy and safety versions are still current. */
    public TaskExecution switchCurrentPlan(
            PlanVersion plan,
            Optional<PlanVersionId> expectedCurrentPlanVersionId,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        TaskExecutionPlanningContext context = planningContext.orElseThrow(() ->
                new DomainValidationException("taskExecution.planningContext", "must be initialized"));
        PlanVersion requiredPlan = Objects.requireNonNull(plan, "plan");
        if (!context.currentPlanVersionId().equals(Objects.requireNonNull(
                        expectedCurrentPlanVersionId, "expectedCurrentPlanVersionId"))
                || !scope.equals(requiredPlan.scope())
                || !taskId.equals(requiredPlan.taskId())
                || !id.equals(requiredPlan.executionId())
                || !context.policySnapshotId().equals(requiredPlan.policySnapshotId())
                || !context.policySnapshotHash().equals(requiredPlan.policySnapshotHash())
                || !context.safetyOverlay().equals(requiredPlan.safetyOverlay())
                || !context.executionPrincipal().equals(requiredPlan.executionPrincipal())) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.currentPlanVersionId",
                    "must select a plan for the current execution, policy, safety and Executor facts");
        }
        if (context.currentPlanVersionId().filter(requiredPlan.id()::equals).isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.currentPlanVersionId",
                    "replacement must identify a new PlanVersion");
        }
        if (context.currentPlanVersionId().isPresent()
                && requiredPlan.parentVersionId()
                        .filter(context.currentPlanVersionId().orElseThrow()::equals)
                        .isEmpty()) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.currentPlanVersionId",
                    "a selected replacement Plan must descend from the current PlanVersion");
        }
        PrincipalId actorId = requireActor(actor);
        return copyWithPlanningContext(
                Optional.of(context.withPlan(requiredPlan)),
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Publishes a newly created attempt to the durable READY queue. */
    public TaskExecution markReady(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                TaskExecutionStatus.READY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                expectedVersion,
                actor,
                occurredAt);
    }

    /** Records that no compatible Runtime is currently available without claiming the attempt. */
    public TaskExecution waitForRuntime(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(TaskExecutionStatus.READY, TaskExecutionStatus.WAITING);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        PrincipalId actorId = requireActor(actor);
        return transitionWithActor(
                TaskExecutionStatus.WAITING,
                Optional.of(new TaskExecutionWaiting(TaskExecutionWaitReason.RUNTIME, requiredTime)),
                Optional.empty(),
                Optional.empty(),
                actorId,
                requiredTime);
    }

    /** Claims a READY attempt; lease ownership facts are attached by M3-D05 in the same command. */
    public TaskExecution claim(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        if (requiredTime.compareTo(notBefore) < 0) {
            throw new DomainValidationException(
                    "taskExecution.notBefore", "must have elapsed before claim");
        }
        requireStatus(TaskExecutionStatus.READY, TaskExecutionStatus.CLAIMED);
        PrincipalId actorId = requireActor(actor);
        FencingToken nextFencingToken = lastFencingToken
                .map(FencingToken::next)
                .orElseGet(FencingToken::initial);
        return new TaskExecution(
                id, scope, taskId, attempt, maxAttempts, parentExecutionId, priority, notBefore,
                TaskExecutionStatus.CLAIMED, Optional.empty(), Optional.empty(), Optional.empty(),
                planningContext, Optional.of(nextFencingToken), version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    public TaskExecution beginPreparing(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return simpleTransition(TaskExecutionStatus.PREPARING, expectedVersion, actor, occurredAt);
    }

    public TaskExecution beginRunning(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return simpleTransition(TaskExecutionStatus.RUNNING, expectedVersion, actor, occurredAt);
    }

    /**
     * Advances the durable execution version for one externally visible progress observation.
     *
     * <p>The bounded summary itself is carried by the corresponding DomainEvent. Keeping it out of
     * the aggregate prevents a high-frequency progress stream from turning the TaskExecution row
     * into an unbounded document while still fencing every observation by execution Version.
     */
    public TaskExecution recordProgress(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != TaskExecutionStatus.RUNNING
                && status != TaskExecutionStatus.PAUSE_REQUESTED
                && status != TaskExecutionStatus.CANCEL_REQUESTED) {
            throw new InvalidStateTransitionException(
                    "TaskExecution", id, status, TaskExecutionStatus.RUNNING);
        }
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return copy(
                priority,
                notBefore,
                status,
                waiting,
                controlRequest,
                terminal,
                version + 1,
                audit.modifiedBy(actorId, requiredTime));
    }

    /** Suspends scheduling until an explicit external condition is resolved. */
    public TaskExecution waitFor(
            TaskExecutionWaitReason reason,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        TaskExecutionWaitReason requiredReason = Objects.requireNonNull(reason, "reason");
        if (requiredReason == TaskExecutionWaitReason.RUNTIME) {
            throw new DomainValidationException(
                    "taskExecution.waiting.reason",
                    "RUNTIME must be entered from READY through waitForRuntime");
        }
        requireStatus(TaskExecutionStatus.RUNNING, TaskExecutionStatus.WAITING);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        PrincipalId actorId = requireActor(actor);
        return transitionWithActor(
                TaskExecutionStatus.WAITING,
                Optional.of(new TaskExecutionWaiting(requiredReason, requiredTime)),
                Optional.empty(),
                Optional.empty(),
                actorId,
                requiredTime);
    }

    /** Requeues an execution after its wait condition, pause or recovery has been resolved. */
    public TaskExecution requeue(
            UtcTimestamp replacementNotBefore,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        if (status != TaskExecutionStatus.WAITING
                && status != TaskExecutionStatus.PAUSED
                && status != TaskExecutionStatus.RECOVERING) {
            throw new InvalidStateTransitionException(
                    "TaskExecution", id, status, TaskExecutionStatus.READY);
        }
        TaskExecution transitioned = transition(
                TaskExecutionStatus.READY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                expectedVersion,
                actor,
                occurredAt);
        return transitioned.copy(
                transitioned.priority,
                Objects.requireNonNull(replacementNotBefore, "replacementNotBefore"),
                transitioned.status,
                transitioned.waiting,
                transitioned.controlRequest,
                transitioned.terminal,
                transitioned.version,
                transitioned.audit);
    }

    /** Updates queue ordering without changing the execution lifecycle. */
    public TaskExecution reschedule(
            TaskExecutionPriority replacementPriority,
            UtcTimestamp replacementNotBefore,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (status != TaskExecutionStatus.CREATED && status != TaskExecutionStatus.READY) {
            throw new InvalidStateTransitionException("TaskExecution", id, status, status);
        }
        TaskExecutionPriority requiredPriority = Objects.requireNonNull(
                replacementPriority, "replacementPriority");
        UtcTimestamp requiredNotBefore = Objects.requireNonNull(
                replacementNotBefore, "replacementNotBefore");
        if (priority.equals(requiredPriority) && notBefore.equals(requiredNotBefore)) {
            throw new DomainValidationException(
                    "taskExecution.schedule", "must differ from the current schedule");
        }
        PrincipalId actorId = requireActor(actor);
        return copy(
                requiredPriority,
                requiredNotBefore,
                status,
                waiting,
                controlRequest,
                terminal,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    /** Requests a safe-point pause while the attempt is actively running. */
    public TaskExecution requestPause(
            String reason, long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(TaskExecutionStatus.RUNNING, TaskExecutionStatus.PAUSE_REQUESTED);
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return transitionWithActor(
                TaskExecutionStatus.PAUSE_REQUESTED,
                Optional.empty(),
                Optional.of(new TaskExecutionControlRequest(
                        TaskExecutionControlRequestType.PAUSE, actorId, requiredTime, reason)),
                Optional.empty(),
                actorId,
                requiredTime);
    }

    /** Confirms the Worker has reached a safe paused point. */
    public TaskExecution acknowledgePaused(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return transition(
                TaskExecutionStatus.PAUSED,
                Optional.empty(),
                controlRequest,
                Optional.empty(),
                expectedVersion,
                actor,
                occurredAt);
    }

    /** Requests cancellation; terminal cancellation is committed separately at a safe point. */
    public TaskExecution requestCancel(
            String reason, long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        if (!TRANSITIONS.get(status).contains(TaskExecutionStatus.CANCEL_REQUESTED)) {
            throw new InvalidStateTransitionException(
                    "TaskExecution", id, status, TaskExecutionStatus.CANCEL_REQUESTED);
        }
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return transitionWithActor(
                TaskExecutionStatus.CANCEL_REQUESTED,
                Optional.empty(),
                Optional.of(new TaskExecutionControlRequest(
                        TaskExecutionControlRequestType.CANCEL, actorId, requiredTime, reason)),
                Optional.empty(),
                actorId,
                requiredTime);
    }

    /** Commits cancellation after all in-flight execution has stopped. */
    public TaskExecution acknowledgeCancelled(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        requireStatus(TaskExecutionStatus.CANCEL_REQUESTED, TaskExecutionStatus.CANCELLED);
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return transitionWithActor(
                TaskExecutionStatus.CANCELLED,
                Optional.empty(),
                controlRequest,
                Optional.of(new TaskExecutionTerminal(
                        TaskExecutionStatus.CANCELLED, actorId, requiredTime, Optional.empty())),
                actorId,
                requiredTime);
    }

    /** Enters recovery after ownership loss or interrupted preparation/execution. */
    public TaskExecution beginRecovery(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return simpleTransition(TaskExecutionStatus.RECOVERING, expectedVersion, actor, occurredAt);
    }

    public TaskExecution beginManualTakeover(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        return simpleTransition(TaskExecutionStatus.MANUAL_TAKEOVER, expectedVersion, actor, occurredAt);
    }

    public TaskExecution complete(
            long expectedVersion, Principal actor, UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return transitionWithActor(
                TaskExecutionStatus.COMPLETED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TaskExecutionTerminal(
                        TaskExecutionStatus.COMPLETED, actorId, requiredTime, Optional.empty())),
                actorId,
                requiredTime);
    }

    public TaskExecution fail(
            TaskExecutionFailure failure,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        PrincipalId actorId = requireActor(actor);
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return transitionWithActor(
                TaskExecutionStatus.FAILED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(new TaskExecutionTerminal(
                        TaskExecutionStatus.FAILED,
                        actorId,
                        requiredTime,
                        Optional.of(Objects.requireNonNull(failure, "failure")))),
                actorId,
                requiredTime);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean canRetry() {
        return status == TaskExecutionStatus.FAILED
                && attempt < maxAttempts
                && terminal.flatMap(TaskExecutionTerminal::failure)
                        .map(TaskExecutionFailure::isRetryable)
                        .orElse(false);
    }

    public TaskExecutionId id() {
        return id;
    }

    public WorkItemScope scope() {
        return scope;
    }

    public TaskId taskId() {
        return taskId;
    }

    public int attempt() {
        return attempt;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Optional<TaskExecutionId> parentExecutionId() {
        return parentExecutionId;
    }

    public TaskExecutionPriority priority() {
        return priority;
    }

    public UtcTimestamp notBefore() {
        return notBefore;
    }

    public TaskExecutionStatus status() {
        return status;
    }

    public Optional<TaskExecutionWaiting> waiting() {
        return waiting;
    }

    public Optional<TaskExecutionControlRequest> controlRequest() {
        return controlRequest;
    }

    public Optional<TaskExecutionTerminal> terminal() {
        return terminal;
    }

    public Optional<TaskExecutionPlanningContext> planningContext() {
        return planningContext;
    }

    /** Last committed ownership epoch; every successful Claim advances it exactly once. */
    public Optional<FencingToken> lastFencingToken() {
        return lastFencingToken;
    }

    public long version() {
        return version;
    }

    public AuditMetadata audit() {
        return audit;
    }

    private TaskExecution simpleTransition(
            TaskExecutionStatus target,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        return transition(
                target,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                expectedVersion,
                actor,
                occurredAt);
    }

    private TaskExecution transition(
            TaskExecutionStatus target,
            Optional<TaskExecutionWaiting> targetWaiting,
            Optional<TaskExecutionControlRequest> targetControlRequest,
            Optional<TaskExecutionTerminal> targetTerminal,
            long expectedVersion,
            Principal actor,
            UtcTimestamp occurredAt) {
        requireExpectedVersion(expectedVersion);
        PrincipalId actorId = requireActor(actor);
        return transitionWithActor(
                target,
                targetWaiting,
                targetControlRequest,
                targetTerminal,
                actorId,
                Objects.requireNonNull(occurredAt, "occurredAt"));
    }

    private TaskExecution transitionWithActor(
            TaskExecutionStatus target,
            Optional<TaskExecutionWaiting> targetWaiting,
            Optional<TaskExecutionControlRequest> targetControlRequest,
            Optional<TaskExecutionTerminal> targetTerminal,
            PrincipalId actorId,
            UtcTimestamp occurredAt) {
        TaskExecutionStatus requiredTarget = Objects.requireNonNull(target, "target");
        if (!TRANSITIONS.get(status).contains(requiredTarget)) {
            throw new InvalidStateTransitionException(
                    "TaskExecution", id, status, requiredTarget);
        }
        return copy(
                priority,
                notBefore,
                requiredTarget,
                targetWaiting,
                targetControlRequest,
                targetTerminal,
                version + 1,
                audit.modifiedBy(actorId, occurredAt));
    }

    private TaskExecution copy(
            TaskExecutionPriority targetPriority,
            UtcTimestamp targetNotBefore,
            TaskExecutionStatus targetStatus,
            Optional<TaskExecutionWaiting> targetWaiting,
            Optional<TaskExecutionControlRequest> targetControlRequest,
            Optional<TaskExecutionTerminal> targetTerminal,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new TaskExecution(
                id,
                scope,
                taskId,
                attempt,
                maxAttempts,
                parentExecutionId,
                targetPriority,
                targetNotBefore,
                targetStatus,
                targetWaiting,
                targetControlRequest,
                targetTerminal,
                planningContext,
                lastFencingToken,
                targetVersion,
                targetAudit);
    }

    private TaskExecution copyWithPlanningContext(
            Optional<TaskExecutionPlanningContext> targetPlanningContext,
            long targetVersion,
            AuditMetadata targetAudit) {
        return new TaskExecution(
                id, scope, taskId, attempt, maxAttempts, parentExecutionId, priority, notBefore,
                status, waiting, controlRequest, terminal, targetPlanningContext,
                lastFencingToken, targetVersion, targetAudit);
    }

    private void requirePlanningLineage(
            PolicySnapshot policy, SafetyEnforcementOverlay overlay) {
        requirePolicyLineage(policy);
        requireOverlayLineage(overlay);
    }

    private void requirePolicyLineage(PolicySnapshot policy) {
        PolicySnapshot required = Objects.requireNonNull(policy, "policy");
        if (!scope.equals(required.scope())
                || !taskId.equals(required.taskId())
                || !id.equals(required.executionId())) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.policySnapshotId",
                    "must belong to this TaskExecution and scope");
        }
    }

    private void requireOverlayLineage(SafetyEnforcementOverlay overlay) {
        SafetyEnforcementOverlay required = Objects.requireNonNull(overlay, "overlay");
        if (!scope.equals(required.scope())
                || !taskId.equals(required.taskId())
                || !id.equals(required.executionId())) {
            throw new DomainValidationException(
                    "taskExecution.planningContext.safetyOverlay",
                    "must belong to this TaskExecution and scope");
        }
    }

    private PrincipalId requireActor(Principal actor) {
        return TaskActorPolicy.requireActiveInScope(
                actor, scope, "taskExecution.updatedByPrincipalId");
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (version != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "TaskExecution", id, expectedVersion, version);
        }
    }

    private void requireStatus(TaskExecutionStatus expected, TaskExecutionStatus target) {
        if (status != expected) {
            throw new InvalidStateTransitionException("TaskExecution", id, status, target);
        }
    }

    private static Task requireOpenTask(Task task) {
        Task required = Objects.requireNonNull(task, "task");
        if (required.isClosed()) {
            throw new DomainValidationException(
                    "taskExecution.taskId", "must reference an open Task");
        }
        return required;
    }

    private static int requireAttempt(int attempt, int maxAttempts) {
        if (attempt < 1 || attempt > maxAttempts) {
            throw new DomainValidationException(
                    "taskExecution.attempt", "must be positive and not exceed maxAttempts");
        }
        return attempt;
    }

    private static boolean requiresActiveOwnership(TaskExecutionStatus status) {
        return status == TaskExecutionStatus.CLAIMED
                || status == TaskExecutionStatus.PREPARING
                || status == TaskExecutionStatus.RUNNING
                || status == TaskExecutionStatus.PAUSE_REQUESTED
                || status == TaskExecutionStatus.RECOVERING;
    }

    private static int requireMaxAttempts(int value) {
        if (value < 1 || value > MAX_SUPPORTED_ATTEMPTS) {
            throw new DomainValidationException(
                    "taskExecution.maxAttempts", "must be between 1 and 100");
        }
        return value;
    }

    private static Optional<TaskExecutionId> requireParent(
            TaskExecutionId id, int attempt, Optional<TaskExecutionId> parentExecutionId) {
        Optional<TaskExecutionId> required = Objects.requireNonNull(
                parentExecutionId, "parentExecutionId");
        if ((attempt == 1) == required.isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.parentExecutionId",
                    attempt == 1 ? "must be empty for attempt one" : "is required after attempt one");
        }
        if (required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.parentExecutionId", "must not reference the execution itself");
        }
        return required;
    }

    private static Optional<TaskExecutionWaiting> requireWaiting(
            TaskExecutionStatus status, Optional<TaskExecutionWaiting> waiting) {
        Optional<TaskExecutionWaiting> required = Objects.requireNonNull(waiting, "waiting");
        if ((status == TaskExecutionStatus.WAITING) != required.isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.waiting",
                    status == TaskExecutionStatus.WAITING
                            ? "is required while WAITING"
                            : "is only allowed while WAITING");
        }
        return required;
    }

    private static Optional<TaskExecutionControlRequest> requireControlRequest(
            TaskExecutionStatus status,
            Optional<TaskExecutionControlRequest> controlRequest) {
        Optional<TaskExecutionControlRequest> required = Objects.requireNonNull(
                controlRequest, "controlRequest");
        boolean pauseShape = status == TaskExecutionStatus.PAUSE_REQUESTED
                || status == TaskExecutionStatus.PAUSED;
        boolean cancelShape = status == TaskExecutionStatus.CANCEL_REQUESTED
                || status == TaskExecutionStatus.CANCELLED;
        if (!pauseShape && !cancelShape && required.isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.controlRequest", "is not allowed for the current status");
        }
        if ((pauseShape || cancelShape) && required.isEmpty()) {
            throw new DomainValidationException(
                    "taskExecution.controlRequest", "is required for the current status");
        }
        required.ifPresent(request -> {
            TaskExecutionControlRequestType expected = pauseShape
                    ? TaskExecutionControlRequestType.PAUSE
                    : TaskExecutionControlRequestType.CANCEL;
            if (request.type() != expected) {
                throw new DomainValidationException(
                        "taskExecution.controlRequest.type", "must match the current status");
            }
        });
        return required;
    }

    private static Optional<TaskExecutionTerminal> requireTerminal(
            TaskExecutionStatus status, Optional<TaskExecutionTerminal> terminal) {
        Optional<TaskExecutionTerminal> required = Objects.requireNonNull(terminal, "terminal");
        if (status.isTerminal() != required.isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.terminal",
                    status.isTerminal()
                            ? "is required for a terminal status"
                            : "is only allowed for a terminal status");
        }
        if (required.filter(value -> value.status() != status).isPresent()) {
            throw new DomainValidationException(
                    "taskExecution.terminal.status", "must match the execution status");
        }
        return required;
    }

    private static void requireTemporalOrder(
            UtcTimestamp notBefore,
            Optional<TaskExecutionWaiting> waiting,
            Optional<TaskExecutionControlRequest> controlRequest,
            Optional<TaskExecutionTerminal> terminal,
            AuditMetadata audit) {
        if (notBefore.compareTo(audit.createdAt()) < 0) {
            throw new DomainValidationException(
                    "taskExecution.notBefore", "must not be before creation time");
        }
        waiting.ifPresent(value -> requireOccurredDuringLifetime(
                "taskExecution.waiting.waitingSince", value.waitingSince(), audit));
        controlRequest.ifPresent(value -> requireOccurredDuringLifetime(
                "taskExecution.controlRequest.requestedAt", value.requestedAt(), audit));
        terminal.ifPresent(value -> requireOccurredDuringLifetime(
                "taskExecution.terminal.decidedAt", value.decidedAt(), audit));
    }

    private static void requireOccurredDuringLifetime(
            String field, UtcTimestamp occurredAt, AuditMetadata audit) {
        if (occurredAt.compareTo(audit.createdAt()) < 0
                || occurredAt.compareTo(audit.updatedAt()) > 0) {
            throw new DomainValidationException(
                    field, "must be within the aggregate audit lifetime");
        }
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "taskExecution.version", "must not be negative");
        }
        return value;
    }
}
