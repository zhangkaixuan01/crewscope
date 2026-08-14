package io.crewscope.domain.task;

import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class TaskPlanningFixture {

    static final UtcTimestamp POLICY_AT = UtcTimestamp.parse("2026-08-13T10:00:00Z");
    static final UtcTimestamp PLAN_AT = UtcTimestamp.parse("2026-08-13T10:05:00Z");
    static final UtcTimestamp STEP_AT = UtcTimestamp.parse("2026-08-13T10:10:00Z");
    static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-13T10:20:00Z");

    final TaskDomainFixture base = new TaskDomainFixture();
    final Task task = base.task();
    final TaskExecution execution = TaskExecution.firstAttempt(
            TaskExecutionId.generate(),
            task,
            3,
            TaskExecutionPriority.NORMAL,
            TaskDomainFixture.CREATED_AT,
            base.owner,
            TaskDomainFixture.CREATED_AT);
    final PolicyPackReference policyPack = new PolicyPackReference(PolicyPackId.generate(), 3);
    final AgentProfileId agentProfileId = AgentProfileId.generate();
    final ProviderBindingId providerBindingId = ProviderBindingId.generate();

    PolicySnapshot policy() {
        return PolicySnapshot.initial(
                PolicySnapshotId.generate(),
                task,
                execution,
                base.executor,
                policyPack,
                agentProfileId,
                4,
                Set.of(ExecutionCapability.PLAN, ExecutionCapability.STRUCTURED_OUTPUT),
                Set.of("repository.read", "validation.run"),
                Set.of(providerBindingId),
                new PolicyBudget(100_000, 20, 50, 3_600),
                base.owner,
                POLICY_AT);
    }

    SafetyEnforcementOverlay overlay() {
        return SafetyEnforcementOverlay.unrestricted(
                SafetyEnforcementOverlayId.generate(), task, execution, base.owner, POLICY_AT);
    }

    ProposedPlan candidate() {
        return ProposedPlan.of(
                "# Plan\n\nAnalyze and validate.",
                List.of(
                        new PlanStep(
                                "analyze",
                                1,
                                "Analyze inputs",
                                PlanStepType.ANALYSIS,
                                Set.of(),
                                Set.of(ExecutionCapability.PLAN),
                                Set.of("repository.read"),
                                true),
                        new PlanStep(
                                "validate",
                                2,
                                "Validate result",
                                PlanStepType.VALIDATION,
                                Set.of("analyze"),
                                Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                                Set.of("validation.run"),
                                true)));
    }

    List<TodoSummaryItem> todo() {
        return List.of(
                new TodoSummaryItem(
                        "Analyze inputs", TodoStatus.IN_PROGRESS, Optional.of("high"),
                        Optional.of("analyze")),
                new TodoSummaryItem(
                        "Validate result", TodoStatus.PENDING, Optional.empty(),
                        Optional.of("validate")));
    }

    PlanningGraph graph() {
        PolicySnapshot policy = policy();
        SafetyEnforcementOverlay overlay = overlay();
        TaskExecution initialized = execution.initializePlanningContext(
                policy, overlay, 0, base.owner, POLICY_AT);
        PlanVersion plan = PlanVersion.publishInitial(
                PlanVersionId.generate(), task, initialized, candidate(), todo(), policy, overlay,
                base.owner, PLAN_AT);
        TaskExecution selected = initialized.switchCurrentPlan(
                plan, Optional.empty(), 1, base.owner, PLAN_AT);
        return new PlanningGraph(policy, overlay, plan, selected);
    }

    record PlanningGraph(
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            PlanVersion plan,
            TaskExecution execution) {}
}
