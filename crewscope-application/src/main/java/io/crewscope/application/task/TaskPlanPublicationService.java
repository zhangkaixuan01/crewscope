package io.crewscope.application.task;

import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.PlanChangeReason;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.PlanVersionId;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionId;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.task.TaskExecutionPlanningContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Publishes an AgentScope plan candidate only after reloading all current durable facts. */
public final class TaskPlanPublicationService {

    /** M3 deliberately permits only deterministic, in-process Fixture Tools. */
    public static final Set<String> M3_CONTROLLED_TOOLS = Set.of(
            "fixture_inspect", "fixture_execute", "fixture_validate");

    private final TaskRepository taskRepository;
    private final TaskExecutionRepository executionRepository;
    private final PlanVersionRepository planRepository;
    private final StepExecutionRepository stepRepository;
    private final PolicySnapshotRepository policyRepository;
    private final SafetyEnforcementOverlayRepository safetyRepository;
    private final PrincipalRepository principalRepository;
    private final TransactionExecutor transactionExecutor;
    private final Clock clock;
    private final Supplier<PlanVersionId> planIdFactory;
    private final Supplier<StepExecutionId> stepIdFactory;

    public TaskPlanPublicationService(
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            PlanVersionRepository planRepository,
            StepExecutionRepository stepRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository safetyRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            Clock clock) {
        this(
                taskRepository,
                executionRepository,
                planRepository,
                stepRepository,
                policyRepository,
                safetyRepository,
                principalRepository,
                transactionExecutor,
                clock,
                PlanVersionId::generate,
                StepExecutionId::generate);
    }

    TaskPlanPublicationService(
            TaskRepository taskRepository,
            TaskExecutionRepository executionRepository,
            PlanVersionRepository planRepository,
            StepExecutionRepository stepRepository,
            PolicySnapshotRepository policyRepository,
            SafetyEnforcementOverlayRepository safetyRepository,
            PrincipalRepository principalRepository,
            TransactionExecutor transactionExecutor,
            Clock clock,
            Supplier<PlanVersionId> planIdFactory,
            Supplier<StepExecutionId> stepIdFactory) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.planRepository = Objects.requireNonNull(planRepository, "planRepository");
        this.stepRepository = Objects.requireNonNull(stepRepository, "stepRepository");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository");
        this.safetyRepository = Objects.requireNonNull(safetyRepository, "safetyRepository");
        this.principalRepository = Objects.requireNonNull(principalRepository, "principalRepository");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.planIdFactory = Objects.requireNonNull(planIdFactory, "planIdFactory");
        this.stepIdFactory = Objects.requireNonNull(stepIdFactory, "stepIdFactory");
    }

    /** The transaction boundary prevents policy, overlay or current-plan time-of-check races. */
    public TaskPlanPublicationResult publish(TaskPlanPublicationCommand command) {
        TaskPlanPublicationCommand required = Objects.requireNonNull(command, "command");
        return transactionExecutor.required(() -> publishInTransaction(required));
    }

    private TaskPlanPublicationResult publishInTransaction(TaskPlanPublicationCommand command) {
        Task task = taskRepository.findById(command.organizationId(), command.taskId())
                .orElseThrow(() -> new AggregateNotFoundException("Task", command.taskId()));
        TaskExecution execution = executionRepository
                .findById(command.organizationId(), command.executionId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", command.executionId()));
        PolicySnapshot policy = policyRepository
                .findById(command.organizationId(), command.policySnapshotId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "PolicySnapshot", command.policySnapshotId()));
        SafetyEnforcementOverlay overlay = safetyRepository
                .findByIdAndVersion(
                        command.organizationId(),
                        command.safetyOverlay().id(),
                        command.safetyOverlay().version())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "SafetyEnforcementOverlay", command.safetyOverlay().id()));
        TaskExecutionPlanningContext planning = execution.planningContext()
                .orElseThrow(() -> stale("TaskExecution planning context is unavailable"));
        Principal actor = principalRepository
                .findById(command.organizationId(), planning.executionPrincipal().principalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", planning.executionPrincipal().principalId()));

        requireCurrentFacts(command, task, execution, policy, overlay, planning);
        requireControlledTools(command);
        Optional<PlanVersion> parent = command.expectedCurrentPlanVersionId()
                .map(id -> planRepository.findById(command.organizationId(), id)
                        .orElseThrow(() -> new AggregateNotFoundException("PlanVersion", id)));
        UtcTimestamp now = UtcTimestamp.from(clock.instant());
        PlanVersion plan = parent
                .map(current -> PlanVersion.publishReplacement(
                        planIdFactory.get(),
                        current,
                        task,
                        execution,
                        command.changeReason(),
                        command.candidate(),
                        command.todoSummary(),
                        policy,
                        overlay,
                        actor,
                        now))
                .orElseGet(() -> PlanVersion.publishInitial(
                        planIdFactory.get(),
                        task,
                        execution,
                        command.candidate(),
                        command.todoSummary(),
                        policy,
                        overlay,
                        actor,
                        now));

        PlanVersion createdPlan = planRepository.create(plan);
        TaskExecution updatedExecution = execution.switchCurrentPlan(
                createdPlan,
                command.expectedCurrentPlanVersionId(),
                command.expectedExecutionVersion(),
                actor,
                now);
        TaskExecution committedExecution = executionRepository.update(updatedExecution);
        List<StepExecution> steps = new ArrayList<>(createdPlan.steps().size());
        createdPlan.steps().forEach(planStep -> steps.add(stepRepository.create(
                StepExecution.create(
                        stepIdFactory.get(),
                        task,
                        committedExecution,
                        createdPlan,
                        planStep,
                        command.maxStepRunAttempts(),
                        actor,
                        now))));
        return new TaskPlanPublicationResult(createdPlan, committedExecution, steps);
    }

    private static void requireCurrentFacts(
            TaskPlanPublicationCommand command,
            Task task,
            TaskExecution execution,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            TaskExecutionPlanningContext planning) {
        boolean current = task.scope().organizationId().equals(command.organizationId())
                && task.id().equals(command.taskId())
                && !task.isClosed()
                && task.currentExecutionId().filter(execution.id()::equals).isPresent()
                && execution.scope().equals(task.scope())
                && execution.taskId().equals(task.id())
                && execution.id().equals(command.executionId())
                && execution.version() == command.expectedExecutionVersion()
                && planning.currentPlanVersionId().equals(command.expectedCurrentPlanVersionId())
                && planning.policySnapshotId().equals(command.policySnapshotId())
                && planning.policySnapshotHash().equals(command.policySnapshotHash())
                && planning.safetyOverlay().equals(command.safetyOverlay())
                && policy.id().equals(command.policySnapshotId())
                && policy.snapshotHash().equals(command.policySnapshotHash())
                && policy.agentProfileId().equals(command.agentProfileId())
                && policy.agentProfileVersion() == command.agentProfileVersion()
                && overlay.reference().equals(command.safetyOverlay());
        if (!current) {
            throw stale("Plan candidate was generated from stale Task execution facts");
        }
    }

    private static void requireControlledTools(TaskPlanPublicationCommand command) {
        Set<String> requested = command.candidate().steps().stream()
                .flatMap(step -> step.requiredTools().stream())
                .collect(Collectors.toUnmodifiableSet());
        if (!M3_CONTROLLED_TOOLS.containsAll(requested)) {
            throw stale("M3 plans may only use controlled Fixture Tools");
        }
    }

    private static DomainValidationException stale(String message) {
        return new DomainValidationException("taskPlanPublication", message);
    }
}
