package io.crewscope.domain.task;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable, validated execution plan published from a Runtime candidate. */
public final class PlanVersion {

    private final PlanVersionId id;
    private final WorkItemScope scope;
    private final TaskId taskId;
    private final TaskExecutionId executionId;
    private final long revision;
    private final Optional<PlanVersionId> parentVersionId;
    private final PlanChangeReason changeReason;
    private final PolicySnapshotId policySnapshotId;
    private final TaskFactHash policySnapshotHash;
    private final SafetyEnforcementOverlayReference safetyOverlay;
    private final ExecutionPrincipalSnapshot executionPrincipal;
    private final String markdown;
    private final TaskFactHash contentHash;
    private final List<PlanStep> steps;
    private final List<TodoSummaryItem> todoSummary;
    private final TaskFactHash versionHash;
    private final PrincipalId publishedByPrincipalId;
    private final UtcTimestamp publishedAt;

    private PlanVersion(
            PlanVersionId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long revision,
            Optional<PlanVersionId> parentVersionId,
            PlanChangeReason changeReason,
            PolicySnapshotId policySnapshotId,
            TaskFactHash policySnapshotHash,
            SafetyEnforcementOverlayReference safetyOverlay,
            ExecutionPrincipalSnapshot executionPrincipal,
            String markdown,
            TaskFactHash contentHash,
            List<PlanStep> steps,
            List<TodoSummaryItem> todoSummary,
            TaskFactHash versionHash,
            PrincipalId publishedByPrincipalId,
            UtcTimestamp publishedAt,
            boolean validateHash) {
        this.id = Objects.requireNonNull(id, "id");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        if (revision < 1) {
            throw new DomainValidationException("planVersion.revision", "must be positive");
        }
        this.revision = revision;
        this.parentVersionId = requireParent(id, revision, parentVersionId);
        this.changeReason = requireReason(revision, changeReason);
        this.policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        this.policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
        this.safetyOverlay = Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        this.executionPrincipal = Objects.requireNonNull(executionPrincipal, "executionPrincipal");
        ProposedPlan candidate = new ProposedPlan(markdown, contentHash, steps);
        this.markdown = candidate.markdown();
        this.contentHash = candidate.contentHash();
        this.steps = validateSteps(candidate.steps());
        this.todoSummary = validateTodo(todoSummary, this.steps);
        this.publishedByPrincipalId = Objects.requireNonNull(
                publishedByPrincipalId, "publishedByPrincipalId");
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
        TaskFactHash expected = calculateHash();
        if (validateHash && !expected.equals(Objects.requireNonNull(versionHash, "versionHash"))) {
            throw new DomainValidationException(
                    "planVersion.versionHash", "must match the canonical published plan");
        }
        this.versionHash = expected;
    }

    /** Publishes the first plan only after policy and real-time safety checks pass. */
    public static PlanVersion publishInitial(
            PlanVersionId id,
            Task task,
            TaskExecution execution,
            ProposedPlan candidate,
            List<TodoSummaryItem> todoSummary,
            PolicySnapshot policySnapshot,
            SafetyEnforcementOverlay safetyOverlay,
            Principal actor,
            UtcTimestamp publishedAt) {
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        if (requiredExecution.planningContext()
                .flatMap(TaskExecutionPlanningContext::currentPlanVersionId)
                .isPresent()) {
            throw new DomainValidationException(
                    "planVersion.parentVersionId",
                    "an initial Plan requires no current PlanVersion");
        }
        return publish(
                id,
                task,
                requiredExecution,
                1,
                Optional.empty(),
                PlanChangeReason.INITIAL_PLAN,
                candidate,
                todoSummary,
                policySnapshot,
                safetyOverlay,
                actor,
                publishedAt);
    }

    /** Publishes a replacement with an explicit immediate parent and monotonically increasing revision. */
    public static PlanVersion publishReplacement(
            PlanVersionId id,
            PlanVersion parent,
            Task task,
            TaskExecution execution,
            PlanChangeReason reason,
            ProposedPlan candidate,
            List<TodoSummaryItem> todoSummary,
            PolicySnapshot policySnapshot,
            SafetyEnforcementOverlay safetyOverlay,
            Principal actor,
            UtcTimestamp publishedAt) {
        PlanVersion requiredParent = Objects.requireNonNull(parent, "parent");
        PlanChangeReason requiredReason = Objects.requireNonNull(reason, "reason");
        if (requiredReason == PlanChangeReason.INITIAL_PLAN) {
            throw new DomainValidationException(
                    "planVersion.changeReason", "INITIAL_PLAN is only valid for revision one");
        }
        PlanVersion replacement = publish(
                id,
                task,
                execution,
                requiredParent.revision + 1,
                Optional.of(requiredParent.id),
                requiredReason,
                candidate,
                todoSummary,
                policySnapshot,
                safetyOverlay,
                actor,
                publishedAt);
        if (!replacement.scope.equals(requiredParent.scope)
                || !replacement.taskId.equals(requiredParent.taskId)
                || !replacement.executionId.equals(requiredParent.executionId)) {
            throw new DomainValidationException(
                    "planVersion.parentVersionId", "must share TaskExecution lineage and scope");
        }
        Optional<PlanVersionId> currentPlanId = execution.planningContext()
                .flatMap(TaskExecutionPlanningContext::currentPlanVersionId);
        if (currentPlanId.isPresent() && currentPlanId.filter(requiredParent.id::equals).isEmpty()) {
            throw new DomainValidationException(
                    "planVersion.parentVersionId",
                    "must descend from the current PlanVersion when one is selected");
        }
        if (replacement.contentHash.equals(requiredParent.contentHash)
                && replacement.steps.equals(requiredParent.steps)
                && replacement.policySnapshotId.equals(requiredParent.policySnapshotId)
                && replacement.safetyOverlay.equals(requiredParent.safetyOverlay)) {
            throw new DomainValidationException(
                    "planVersion", "must differ from the parent plan content or steps");
        }
        return replacement;
    }

    public static PlanVersion reconstitute(
            PlanVersionId id,
            WorkItemScope scope,
            TaskId taskId,
            TaskExecutionId executionId,
            long revision,
            Optional<PlanVersionId> parentVersionId,
            PlanChangeReason changeReason,
            PolicySnapshotId policySnapshotId,
            TaskFactHash policySnapshotHash,
            SafetyEnforcementOverlayReference safetyOverlay,
            ExecutionPrincipalSnapshot executionPrincipal,
            String markdown,
            TaskFactHash contentHash,
            List<PlanStep> steps,
            List<TodoSummaryItem> todoSummary,
            TaskFactHash versionHash,
            PrincipalId publishedByPrincipalId,
            UtcTimestamp publishedAt) {
        return new PlanVersion(
                id, scope, taskId, executionId, revision, parentVersionId, changeReason,
                policySnapshotId, policySnapshotHash, safetyOverlay, executionPrincipal,
                markdown, contentHash, steps, todoSummary, versionHash,
                publishedByPrincipalId, publishedAt, true);
    }

    private static PlanVersion publish(
            PlanVersionId id,
            Task task,
            TaskExecution execution,
            long revision,
            Optional<PlanVersionId> parentVersionId,
            PlanChangeReason changeReason,
            ProposedPlan candidate,
            List<TodoSummaryItem> todoSummary,
            PolicySnapshot policySnapshot,
            SafetyEnforcementOverlay safetyOverlay,
            Principal actor,
            UtcTimestamp publishedAt) {
        Task requiredTask = Objects.requireNonNull(task, "task");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        PolicySnapshot requiredPolicy = Objects.requireNonNull(policySnapshot, "policySnapshot");
        SafetyEnforcementOverlay requiredOverlay = Objects.requireNonNull(
                safetyOverlay, "safetyOverlay");
        TaskExecutionPlanningContext planningContext = requiredExecution.planningContext()
                .orElseThrow(() -> new DomainValidationException(
                        "planVersion.executionId", "must have initialized planning facts"));
        if (!requiredTask.scope().equals(requiredExecution.scope())
                || !requiredTask.id().equals(requiredExecution.taskId())
                || !requiredPolicy.scope().equals(requiredTask.scope())
                || !requiredPolicy.taskId().equals(requiredTask.id())
                || !requiredPolicy.executionId().equals(requiredExecution.id())
                || !requiredOverlay.scope().equals(requiredTask.scope())
                || !requiredOverlay.taskId().equals(requiredTask.id())
                || !requiredOverlay.executionId().equals(requiredExecution.id())
                || !planningContext.policySnapshotId().equals(requiredPolicy.id())
                || !planningContext.policySnapshotHash().equals(requiredPolicy.snapshotHash())
                || !planningContext.safetyOverlay().equals(requiredOverlay.reference())
                || !planningContext.executionPrincipal().equals(requiredPolicy.executionPrincipal())) {
            throw new DomainValidationException(
                    "planVersion.executionId",
                    "must use the TaskExecution current policy, safety and Executor facts");
        }
        ProposedPlan requiredCandidate = Objects.requireNonNull(candidate, "candidate");
        Set<ExecutionCapability> capabilities = requiredCandidate.steps().stream()
                .flatMap(step -> step.requiredCapabilities().stream())
                .collect(Collectors.toUnmodifiableSet());
        Set<String> tools = requiredCandidate.steps().stream()
                .flatMap(step -> step.requiredTools().stream())
                .collect(Collectors.toUnmodifiableSet());
        if (!requiredOverlay.permits(requiredPolicy, capabilities, tools)) {
            throw new DomainValidationException(
                    "planVersion.policySnapshotId",
                    "must authorize every plan capability and Tool under the current safety overlay");
        }
        PrincipalId actorId = TaskActorPolicy.requireActiveInScope(
                actor, requiredTask.scope(), "planVersion.publishedByPrincipalId");
        return new PlanVersion(
                id,
                requiredTask.scope(),
                requiredTask.id(),
                requiredExecution.id(),
                revision,
                parentVersionId,
                changeReason,
                requiredPolicy.id(),
                requiredPolicy.snapshotHash(),
                requiredOverlay.reference(),
                requiredPolicy.executionPrincipal(),
                requiredCandidate.markdown(),
                requiredCandidate.contentHash(),
                requiredCandidate.steps(),
                todoSummary,
                TaskFactHash.sha256("placeholder"),
                actorId,
                publishedAt,
                false);
    }

    private static List<PlanStep> validateSteps(List<PlanStep> values) {
        List<PlanStep> required = List.copyOf(Objects.requireNonNull(values, "steps"));
        Set<String> keys = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        for (PlanStep step : required) {
            if (!keys.add(step.key()) || !sequences.add(step.sequence())) {
                throw new DomainValidationException(
                        "planVersion.steps", "must have unique keys and sequences");
            }
        }
        for (int expected = 1; expected <= required.size(); expected++) {
            if (!sequences.contains(expected)) {
                throw new DomainValidationException(
                        "planVersion.steps", "sequences must be contiguous from one");
            }
        }
        java.util.Map<String, Integer> sequenceByKey = required.stream()
                .collect(Collectors.toMap(PlanStep::key, PlanStep::sequence));
        for (PlanStep step : required) {
            for (String dependency : step.dependencyKeys()) {
                Integer dependencySequence = sequenceByKey.get(dependency);
                if (dependencySequence == null || dependencySequence >= step.sequence()) {
                    throw new DomainValidationException(
                            "planVersion.steps", "dependencies must reference an earlier step");
                }
            }
        }
        if (required.stream().noneMatch(step -> step.type() == PlanStepType.VALIDATION)) {
            throw new DomainValidationException(
                    "planVersion.steps", "must include at least one validation step");
        }
        return required.stream().sorted(java.util.Comparator.comparingInt(PlanStep::sequence)).toList();
    }

    private static List<TodoSummaryItem> validateTodo(
            List<TodoSummaryItem> values, List<PlanStep> steps) {
        List<TodoSummaryItem> required = List.copyOf(Objects.requireNonNull(values, "todoSummary"));
        if (required.size() > 100
                || required.stream().filter(item -> item.status() == TodoStatus.IN_PROGRESS).count() > 1) {
            throw new DomainValidationException(
                    "planVersion.todoSummary", "must contain at most 100 items and one in progress");
        }
        Set<String> stepKeys = steps.stream().map(PlanStep::key).collect(Collectors.toSet());
        if (required.stream().flatMap(item -> item.planStepKey().stream())
                .anyMatch(key -> !stepKeys.contains(key))) {
            throw new DomainValidationException(
                    "planVersion.todoSummary", "must only reference published Plan steps");
        }
        return required;
    }

    private TaskFactHash calculateHash() {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, id, scope.organizationId(), scope.teamId(), scope.workspaceId(),
                scope.projectId(), taskId, executionId, revision,
                parentVersionId.map(Object::toString).orElse("-"), changeReason,
                policySnapshotId, policySnapshotHash, safetyOverlay.id(), safetyOverlay.version(),
                safetyOverlay.overlayHash(), executionPrincipal.principalId(),
                executionPrincipal.assignmentId(), executionPrincipal.assignmentVersion(),
                executionPrincipal.responsibilitySnapshotHash(), markdown, contentHash);
        steps.forEach(step -> appendCanonical(canonical, step.key(), step.sequence(), step.title(),
                step.type(), sorted(step.dependencyKeys()), sorted(step.requiredCapabilities()),
                sorted(step.requiredTools()), step.critical()));
        todoSummary.forEach(item -> appendCanonical(canonical, item.content(), item.status(),
                item.priority().orElse("-"), item.planStepKey().orElse("-")));
        appendCanonical(canonical, publishedByPrincipalId, publishedAt);
        return TaskFactHash.sha256(canonical.toString());
    }

    /** Length-prefixing keeps free-form Markdown, titles and Todo text collision-safe. */
    private static void appendCanonical(StringBuilder target, Object... values) {
        for (Object value : values) {
            String text = Objects.requireNonNull(value, "canonical value").toString();
            target.append(text.length()).append(':').append(text);
        }
    }

    private static Optional<PlanVersionId> requireParent(
            PlanVersionId id, long revision, Optional<PlanVersionId> parent) {
        Optional<PlanVersionId> required = Objects.requireNonNull(parent, "parentVersionId");
        if ((revision == 1) == required.isPresent() || required.filter(id::equals).isPresent()) {
            throw new DomainValidationException(
                    "planVersion.parentVersionId", "must match revision lineage and not reference itself");
        }
        return required;
    }

    private static PlanChangeReason requireReason(long revision, PlanChangeReason reason) {
        PlanChangeReason required = Objects.requireNonNull(reason, "changeReason");
        if ((revision == 1) != (required == PlanChangeReason.INITIAL_PLAN)) {
            throw new DomainValidationException(
                    "planVersion.changeReason", "must match initial or replacement revision");
        }
        return required;
    }

    private static String sorted(Set<?> values) {
        return values.stream().map(Object::toString).sorted().collect(Collectors.joining(","));
    }

    public PlanVersionId id() { return id; }
    public WorkItemScope scope() { return scope; }
    public TaskId taskId() { return taskId; }
    public TaskExecutionId executionId() { return executionId; }
    public long revision() { return revision; }
    public Optional<PlanVersionId> parentVersionId() { return parentVersionId; }
    public PlanChangeReason changeReason() { return changeReason; }
    public PolicySnapshotId policySnapshotId() { return policySnapshotId; }
    public TaskFactHash policySnapshotHash() { return policySnapshotHash; }
    public SafetyEnforcementOverlayReference safetyOverlay() { return safetyOverlay; }
    public ExecutionPrincipalSnapshot executionPrincipal() { return executionPrincipal; }
    public String markdown() { return markdown; }
    public TaskFactHash contentHash() { return contentHash; }
    public List<PlanStep> steps() { return steps; }
    public List<TodoSummaryItem> todoSummary() { return todoSummary; }
    public TaskFactHash versionHash() { return versionHash; }
    public PrincipalId publishedByPrincipalId() { return publishedByPrincipalId; }
    public UtcTimestamp publishedAt() { return publishedAt; }
}
