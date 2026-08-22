package io.crewscope.server.config.runtime;

import io.crewscope.agentscope.coding.CodingSpecialistStepRequest;
import io.crewscope.agentscope.coding.CodingSpecialistStepResult;
import io.crewscope.agentscope.coding.CodingSpecialistStepRuntime;
import io.crewscope.agentscope.coding.CodingSpecialistStepStatus;
import io.crewscope.agentscope.coding.CodingSpecialistControlAction;
import io.crewscope.agentscope.coding.CodingSpecialistControlResult;
import io.crewscope.application.coding.TestEvidenceRepository;
import io.crewscope.application.execution.ExecutionInterruptToken;
import io.crewscope.application.execution.ExecutionFailure;
import io.crewscope.application.execution.ExecutionFailureCategory;
import io.crewscope.application.execution.TaskExecutionControlAction;
import io.crewscope.application.execution.TaskExecutionControlResult;
import io.crewscope.application.execution.TaskExecutionEvent;
import io.crewscope.application.execution.TaskExecutionEventPayload;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.application.execution.TaskExecutionTerminalStatus;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.task.AgentRunRepository;
import io.crewscope.application.task.ExecutionLeaseRepository;
import io.crewscope.application.task.PlanVersionRepository;
import io.crewscope.application.task.SafetyEnforcementOverlayRepository;
import io.crewscope.application.task.StepExecutionRepository;
import io.crewscope.application.task.TaskAgentRuntimeSessionRepository;
import io.crewscope.application.task.TaskExecutionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.transaction.AuthoritativeTimeProvider;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.task.AgentRun;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PlanStep;
import io.crewscope.domain.task.PlanStepType;
import io.crewscope.domain.task.PlanVersion;
import io.crewscope.domain.task.StepExecution;
import io.crewscope.domain.task.StepExecutionStatus;
import io.crewscope.domain.task.TaskAgentRuntimeSession;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.infrastructure.runtime.TaskWorkerPreparedExecution;
import io.crewscope.infrastructure.runtime.TaskWorkerSpecialistExecution;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Routes one completed Coding Task plan into its durable Coding Specialist Step. */
final class DurableCodingTaskRouter implements TaskWorkerSpecialistExecution {

    private static final Set<ExecutionCapability> CODING_CAPABILITIES = Set.of(
            ExecutionCapability.WORKTREE, ExecutionCapability.SANDBOX);

    private final CodingSpecialistStepRuntime runtime;
    private final TaskExecutionRepository executionRepository;
    private final ExecutionLeaseRepository leaseRepository;
    private final PlanVersionRepository planRepository;
    private final SafetyEnforcementOverlayRepository overlayRepository;
    private final StepExecutionRepository stepRepository;
    private final TaskAgentRuntimeSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final PrincipalRepository principalRepository;
    private final AgentProfileRepository profileRepository;
    private final TestEvidenceRepository testEvidenceRepository;
    private final TransactionExecutor transactionExecutor;
    private final AuthoritativeTimeProvider timeProvider;
    private final int recoveryCandidateLimit;
    private final ConcurrentMap<io.crewscope.domain.task.TaskExecutionId, ActiveSpecialist> active =
            new ConcurrentHashMap<>();

    DurableCodingTaskRouter(
            CodingSpecialistStepRuntime runtime,
            TaskExecutionRepository executionRepository,
            ExecutionLeaseRepository leaseRepository,
            PlanVersionRepository planRepository,
            SafetyEnforcementOverlayRepository overlayRepository,
            StepExecutionRepository stepRepository,
            TaskAgentRuntimeSessionRepository sessionRepository,
            AgentRunRepository runRepository,
            PrincipalRepository principalRepository,
            AgentProfileRepository profileRepository,
            TestEvidenceRepository testEvidenceRepository,
            TransactionExecutor transactionExecutor,
            AuthoritativeTimeProvider timeProvider,
            int recoveryCandidateLimit) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.executionRepository = Objects.requireNonNull(
                executionRepository, "executionRepository");
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.planRepository = Objects.requireNonNull(planRepository, "planRepository");
        this.overlayRepository = Objects.requireNonNull(overlayRepository, "overlayRepository");
        this.stepRepository = Objects.requireNonNull(stepRepository, "stepRepository");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.principalRepository = Objects.requireNonNull(
                principalRepository, "principalRepository");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository");
        this.testEvidenceRepository = Objects.requireNonNull(
                testEvidenceRepository, "testEvidenceRepository");
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor, "transactionExecutor");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        if (recoveryCandidateLimit < 1 || recoveryCandidateLimit > 100) {
            throw new IllegalArgumentException("recoveryCandidateLimit must be between 1 and 100");
        }
        this.recoveryCandidateLimit = recoveryCandidateLimit;
    }

    @Override
    public TaskExecutionEvent executeAfterTaskAgent(
            TaskWorkerPreparedExecution prepared, TaskExecutionEvent taskTerminal) {
        TaskWorkerPreparedExecution required = Objects.requireNonNull(prepared, "prepared");
        TaskExecutionEvent terminal = TaskWorkerSpecialistExecution.requireTerminal(taskTerminal);
        if (terminal.payload().terminalStatus().orElseThrow()
                != TaskExecutionTerminalStatus.COMPLETED) {
            return terminal;
        }
        if (!isCodingTask(required)) {
            return terminal;
        }
        if (required.codingWorkspace().isEmpty()) {
            return routingFailure(
                    terminal,
                    "CODING_WORKSPACE_MISSING",
                    "Coding Task completed planning without an ExecutionWorkspace");
        }

        SpecialistInvocation invocation;
        try {
            invocation = transactionExecutor.required(() -> prepareInvocation(required));
        } catch (CodingRoutingValidationException invalid) {
            return routingFailure(terminal, invalid.code(), invalid.getMessage());
        }
        ActiveSpecialist activeSpecialist = new ActiveSpecialist(invocation);
        if (active.putIfAbsent(invocation.facts().execution().id(), activeSpecialist) != null) {
            return routingFailure(
                    terminal,
                    "CODING_SPECIALIST_ALREADY_ACTIVE",
                    "Coding Task already has an active Specialist execution");
        }
        CodingSpecialistStepResult result;
        try {
            result = Objects.requireNonNull(
                    runtime.execute(new CodingSpecialistStepRequest(
                                    invocation.facts(),
                                    invocation.executor(),
                                    1,
                                    required.correlationId(),
                                    false,
                                    recoveryCandidateLimit))
                            .block(),
                    "Coding Specialist result");
        } finally {
            active.remove(invocation.facts().execution().id(), activeSpecialist);
        }
        if (result.status() == CodingSpecialistStepStatus.SUCCEEDED) {
            try {
                transactionExecutor.required(() -> {
                    completeValidation(required, invocation);
                    return null;
                });
            } catch (CodingRoutingValidationException invalid) {
                return routingFailure(terminal, invalid.code(), invalid.getMessage());
            }
        }
        return terminalWith(terminal, result, activeSpecialist.pauseToken());
    }

    @Override
    public Optional<TaskExecutionControlResult> controlTask(
            TaskWorkerPreparedExecution prepared,
            TaskExecutionControlAction action,
            UUID controlRequestId,
            String reason) {
        Objects.requireNonNull(prepared, "prepared");
        TaskExecutionControlAction requiredAction = Objects.requireNonNull(action, "action");
        UUID requestId = Objects.requireNonNull(controlRequestId, "controlRequestId");
        if (requiredAction == TaskExecutionControlAction.RESUME) {
            return Optional.empty();
        }
        ActiveSpecialist specialist = active.get(prepared.facts().execution().id());
        if (specialist == null) {
            return Optional.empty();
        }
        return Optional.of(specialist.control(requiredAction, requestId, reason));
    }

    private static boolean isCodingTask(TaskWorkerPreparedExecution prepared) {
        return prepared.facts().policySnapshot().capabilities().containsAll(CODING_CAPABILITIES);
    }

    private SpecialistInvocation prepareInvocation(TaskWorkerPreparedExecution prepared) {
        var organizationId = prepared.facts().task().scope().organizationId();
        TaskExecution execution = executionRepository.findById(
                        organizationId, prepared.facts().execution().id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TaskExecution", prepared.facts().execution().id()));
        var planning = execution.planningContext().orElseThrow(() -> routingInvalid(
                "CODING_PLAN_MISSING", "Coding Task has no PlanningContext"));
        PlanVersion plan = planning.currentPlanVersionId()
                .flatMap(id -> planRepository.findById(organizationId, id))
                .orElseThrow(() -> routingInvalid(
                        "CODING_PLAN_MISSING",
                        "Coding Task Agent completed without a published PlanVersion"));
        List<PlanStep> codingSteps = plan.steps().stream()
                .filter(step -> step.type() == PlanStepType.IMPLEMENTATION)
                .toList();
        if (codingSteps.size() != 1) {
            throw routingInvalid(
                    "CODING_IMPLEMENTATION_STEP_INVALID",
                    "Coding Task plan must contain exactly one IMPLEMENTATION Step");
        }
        List<PlanStep> validationSteps = plan.steps().stream()
                .filter(step -> step.type() == PlanStepType.VALIDATION)
                .toList();
        if (validationSteps.size() != 1) {
            throw routingInvalid(
                    "CODING_VALIDATION_STEP_INVALID",
                    "Coding Task plan must contain exactly one VALIDATION Step");
        }
        if (plan.steps().size() != 2) {
            throw routingInvalid(
                    "CODING_PLAN_SHAPE_INVALID",
                    "Coding Task plan must contain only IMPLEMENTATION and VALIDATION Steps");
        }
        String codingStepKey = codingSteps.get(0).key();
        String validationStepKey = validationSteps.get(0).key();
        List<StepExecution> currentSteps = stepRepository.findByExecution(
                        organizationId, execution.id())
                .stream()
                .filter(step -> step.planVersionId().equals(plan.id()))
                .sorted(Comparator.comparingInt(StepExecution::sequence))
                .toList();
        StepExecution codingStep = currentSteps.stream()
                .filter(step -> step.planStepKey().equals(codingStepKey))
                .findFirst()
                .orElseThrow(() -> routingInvalid(
                        "CODING_IMPLEMENTATION_STEP_MISSING",
                        "Published Coding Step has no StepExecution"));
        StepExecution validationStep = currentSteps.stream()
                .filter(step -> step.planStepKey().equals(validationStepKey))
                .findFirst()
                .orElseThrow(() -> routingInvalid(
                        "CODING_VALIDATION_STEP_MISSING",
                        "Published Validation Step has no StepExecution"));
        Principal executor = principalRepository.findById(
                        organizationId, prepared.facts().policySnapshot()
                                .executionPrincipal().principalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal",
                        prepared.facts().policySnapshot().executionPrincipal().principalId()));

        // The Specialist owns implementation and test execution, but the critical Validation Step
        // remains pending until successful platform TestEvidence exists after the Specialist call.
        if (validationStep.status() != StepExecutionStatus.PENDING) {
            throw routingInvalid(
                    "CODING_VALIDATION_STEP_NOT_PENDING",
                    "Validation Step must remain PENDING before Coding Specialist execution");
        }
        if (codingStep.status() == StepExecutionStatus.PENDING
                || codingStep.status() == StepExecutionStatus.FAILED_RETRYABLE) {
            codingStep = stepRepository.update(codingStep.markReady(
                    codingStep.version(), executor, timeProvider.now()));
        }
        if (codingStep.status() != StepExecutionStatus.READY
                && codingStep.status() != StepExecutionStatus.RUNNING
                && codingStep.status() != StepExecutionStatus.WAITING) {
            throw routingInvalid(
                    "CODING_IMPLEMENTATION_STEP_NOT_EXECUTABLE",
                    "Coding Step is not executable");
        }

        AgentProfile profile = profileRepository.findById(
                        organizationId, prepared.facts().policySnapshot().agentProfileId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "AgentProfile", prepared.facts().policySnapshot().agentProfileId()));
        TaskAgentRuntimeSession session = sessionRepository.initializeIfAbsent(
                TaskAgentRuntimeSession.initializeSpecialist(
                        prepared.facts().task(), execution, codingStep, profile, executor,
                        timeProvider.now()));
        AgentRun run = runRepository.findActiveBySession(organizationId, session.id())
                .orElseGet(() -> runRepository.createNext(AgentRun.start(
                        AgentRunId.generate(),
                        session,
                        nextRunSequence(organizationId, execution),
                        executor,
                        timeProvider.now())));
        var lease = leaseRepository.findById(
                        organizationId,
                        prepared.facts().lease().environment(),
                        prepared.facts().lease().id())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ExecutionLease", prepared.facts().lease().id()));
        var overlay = overlayRepository.findByIdAndVersion(
                        organizationId,
                        planning.safetyOverlay().id(),
                        planning.safetyOverlay().version())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "SafetyEnforcementOverlay", planning.safetyOverlay().id()));
        TaskExecutionRuntimeFacts facts = new TaskExecutionRuntimeFacts(
                prepared.facts().task(),
                execution,
                Optional.of(codingStep),
                lease,
                session,
                run,
                prepared.facts().policySnapshot(),
                overlay,
                Optional.of(plan),
                prepared.token().context());
        return new SpecialistInvocation(facts, executor, validationStep.id());
    }

    private void completeValidation(
            TaskWorkerPreparedExecution prepared, SpecialistInvocation invocation) {
        StepExecution implementation = stepRepository.findById(
                        prepared.facts().task().scope().organizationId(),
                        invocation.facts().stepExecution().orElseThrow().id())
                .orElseThrow(() -> routingInvalid(
                        "CODING_IMPLEMENTATION_STEP_MISSING",
                        "Coding Implementation Step disappeared after Specialist execution"));
        if (implementation.status() != StepExecutionStatus.SUCCEEDED) {
            throw routingInvalid(
                    "CODING_IMPLEMENTATION_NOT_SUCCEEDED",
                    "Coding Specialist returned success without a succeeded Implementation Step");
        }

        var workspace = prepared.codingWorkspace().orElseThrow().workspace();
        List<io.crewscope.domain.coding.TestEvidence> evidence =
                testEvidenceRepository.findByWorkspace(
                        workspace.scope().organizationId(),
                        workspace.scope().teamId(),
                        workspace.scope().projectId(),
                        workspace.id());
        if (evidence.isEmpty() || !evidence.get(evidence.size() - 1).succeeded()) {
            throw routingInvalid(
                    "CODING_TEST_EVIDENCE_MISSING",
                    "Coding Specialist returned success without current successful TestEvidence");
        }

        StepExecution validation = stepRepository.findById(
                        prepared.facts().task().scope().organizationId(),
                        invocation.validationStepId())
                .orElseThrow(() -> routingInvalid(
                        "CODING_VALIDATION_STEP_MISSING",
                        "Coding Validation Step disappeared after Specialist execution"));
        if (validation.status() == StepExecutionStatus.SUCCEEDED) {
            return;
        }
        if (validation.status() == StepExecutionStatus.PENDING) {
            validation = stepRepository.update(validation.markReady(
                    validation.version(), invocation.executor(), timeProvider.now()));
        }
        if (validation.status() == StepExecutionStatus.READY) {
            validation = stepRepository.update(validation.beginRunning(
                    validation.version(), invocation.executor(), timeProvider.now()));
        }
        if (validation.status() == StepExecutionStatus.RUNNING) {
            stepRepository.update(validation.succeed(
                    validation.version(), invocation.executor(), timeProvider.now()));
            return;
        }
        throw routingInvalid(
                "CODING_VALIDATION_STEP_NOT_EXECUTABLE",
                "Coding Validation Step cannot converge after successful evidence");
    }

    private long nextRunSequence(
            io.crewscope.domain.shared.id.OrganizationId organizationId,
            TaskExecution execution) {
        return runRepository.findByExecution(organizationId, execution.id()).stream()
                .map(AgentRun::runSequence)
                .max(Comparator.naturalOrder())
                .orElse(0L) + 1L;
    }

    private TaskExecutionEvent terminalWith(
            TaskExecutionEvent source,
            CodingSpecialistStepResult result,
            Optional<ExecutionInterruptToken> pauseToken) {
        TaskExecutionEventPayload payload = switch (result.status()) {
            case SUCCEEDED -> source.payload();
            case CANCELLED -> new TaskExecutionEventPayload.Canceled(
                    "Coding Specialist execution was cancelled");
            case PAUSED -> new TaskExecutionEventPayload.Paused(
                    pauseToken.orElseThrow(() -> new IllegalStateException(
                            "Paused Coding Specialist has no durable interrupt token")),
                    "Coding Specialist paused at a recoverable safe point");
            case FAILED -> new TaskExecutionEventPayload.Failed(new ExecutionFailure(
                    ExecutionFailureCategory.VALIDATION,
                    false,
                    "Coding Specialist execution did not complete successfully",
                    result.failureCode()));
        };
        if (result.status() == CodingSpecialistStepStatus.SUCCEEDED) {
            return source;
        }
        return new TaskExecutionEvent(
                source.taskExecutionId(),
                source.attempt(),
                source.agentRunId(),
                source.segmentSequence(),
                source.sequence(),
                transactionExecutor.required(timeProvider::now),
                payload);
    }

    private TaskExecutionEvent routingFailure(
            TaskExecutionEvent source, String code, String message) {
        return new TaskExecutionEvent(
                source.taskExecutionId(),
                source.attempt(),
                source.agentRunId(),
                source.segmentSequence(),
                source.sequence(),
                transactionExecutor.required(timeProvider::now),
                new TaskExecutionEventPayload.Failed(new ExecutionFailure(
                        ExecutionFailureCategory.VALIDATION,
                        false,
                        message,
                        Optional.of(code))));
    }

    private static CodingRoutingValidationException routingInvalid(
            String code, String message) {
        return new CodingRoutingValidationException(code, message);
    }

    private record SpecialistInvocation(
            TaskExecutionRuntimeFacts facts,
            Principal executor,
            io.crewscope.domain.task.StepExecutionId validationStepId) {
        private SpecialistInvocation {
            facts = Objects.requireNonNull(facts, "facts");
            executor = Objects.requireNonNull(executor, "executor");
            validationStepId = Objects.requireNonNull(validationStepId, "validationStepId");
        }
    }

    /** Serializes idempotent durable control delivery for one active Specialist Session. */
    private final class ActiveSpecialist {
        private final SpecialistInvocation invocation;
        private UUID appliedRequestId;
        private TaskExecutionControlAction appliedAction;

        private ActiveSpecialist(SpecialistInvocation invocation) {
            this.invocation = Objects.requireNonNull(invocation, "invocation");
        }

        private synchronized TaskExecutionControlResult control(
                TaskExecutionControlAction action, UUID requestId, String reason) {
            if (requestId.equals(appliedRequestId) && action == appliedAction) {
                return TaskExecutionControlResult.ALREADY_APPLIED;
            }
            if (appliedRequestId != null) {
                return TaskExecutionControlResult.ALREADY_TERMINAL;
            }
            CodingSpecialistControlAction specialistAction =
                    action == TaskExecutionControlAction.PAUSE
                            ? CodingSpecialistControlAction.PAUSE
                            : CodingSpecialistControlAction.CANCEL;
            Optional<ExecutionInterruptToken> pauseToken =
                    action == TaskExecutionControlAction.PAUSE
                            ? Optional.of(new ExecutionInterruptToken(requestId.toString()))
                            : Optional.empty();
            CodingSpecialistControlResult result = runtime.control(
                    invocation.facts(), specialistAction, pauseToken, reason);
            if (!result.accepted()) {
                return TaskExecutionControlResult.ALREADY_TERMINAL;
            }
            appliedRequestId = requestId;
            appliedAction = action;
            return TaskExecutionControlResult.ACCEPTED;
        }

        private synchronized Optional<ExecutionInterruptToken> pauseToken() {
            return appliedAction == TaskExecutionControlAction.PAUSE
                    ? Optional.of(new ExecutionInterruptToken(appliedRequestId.toString()))
                    : Optional.empty();
        }
    }

    private static final class CodingRoutingValidationException extends RuntimeException {
        private final String code;

        private CodingRoutingValidationException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        private String code() {
            return code;
        }
    }
}
