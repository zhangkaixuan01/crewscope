package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanVersionTest {

    @Test
    void publishesValidatedPlanWithPolicyOverlayExecutorAndTodoMapping() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();

        PlanVersion plan = graph.plan();

        assertEquals(1, plan.revision());
        assertTrue(plan.parentVersionId().isEmpty());
        assertEquals(graph.policy().id(), plan.policySnapshotId());
        assertEquals(graph.overlay().reference(), plan.safetyOverlay());
        assertEquals(fixture.base.executor.id(), plan.executionPrincipal().principalId());
        assertEquals(List.of("analyze", "validate"),
                plan.steps().stream().map(PlanStep::key).toList());
        assertEquals(Optional.of("analyze"), plan.todoSummary().get(0).planStepKey());
        assertEquals(64, plan.versionHash().value().length());
    }

    @Test
    void rejectsMissingValidationDuplicateSequenceAndForwardDependency() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot policy = fixture.policy();
        SafetyEnforcementOverlay overlay = fixture.overlay();

        ProposedPlan missingValidation = ProposedPlan.of(
                "# Missing validation",
                List.of(new PlanStep(
                        "analyze", 1, "Analyze", PlanStepType.ANALYSIS, Set.of(),
                        Set.of(ExecutionCapability.PLAN), Set.of("repository.read"), true)));
        ProposedPlan forwardDependency = ProposedPlan.of(
                "# Forward dependency",
                List.of(
                        new PlanStep(
                                "analyze", 1, "Analyze", PlanStepType.ANALYSIS,
                                Set.of("validate"), Set.of(ExecutionCapability.PLAN),
                                Set.of("repository.read"), true),
                        new PlanStep(
                                "validate", 2, "Validate", PlanStepType.VALIDATION,
                                Set.of(), Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                                Set.of("validation.run"), true)));

        assertThrows(DomainValidationException.class, () -> publish(fixture, policy, overlay, missingValidation));
        assertThrows(DomainValidationException.class, () -> publish(fixture, policy, overlay, forwardDependency));
    }

    @Test
    void rejectsPlanCapabilitiesOutsidePolicyOrCurrentSafety() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot policy = fixture.policy();
        SafetyEnforcementOverlay overlay = fixture.overlay();
        ProposedPlan requiresSandbox = ProposedPlan.of(
                "# Sandbox plan",
                List.of(new PlanStep(
                        "validate", 1, "Validate", PlanStepType.VALIDATION, Set.of(),
                        Set.of(ExecutionCapability.SANDBOX), Set.of("validation.run"), true)));
        SafetyEnforcementOverlay tightened = overlay.tighten(
                Set.of(SafetyRestriction.TOOL_DISABLED), Set.of(), Set.of("validation.run"),
                fixture.base.owner, TaskPlanningFixture.LATER);

        assertThrows(DomainValidationException.class, () -> publish(fixture, policy, overlay, requiresSandbox));
        assertThrows(DomainValidationException.class, () -> publish(fixture, policy, tightened, fixture.candidate()));
    }

    @Test
    void requiresCurrentPolicyThenAllowsPlanAfterAnExplicitPolicyRevision() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot initial = fixture.policy();
        SafetyEnforcementOverlay overlay = fixture.overlay();
        TaskExecution initialized = fixture.execution.initializePlanningContext(
                initial, overlay, 0, fixture.base.owner, TaskPlanningFixture.POLICY_AT);
        ProposedPlan requiresSandbox = ProposedPlan.of(
                "# Sandbox plan",
                List.of(new PlanStep(
                        "validate", 1, "Validate in sandbox", PlanStepType.VALIDATION, Set.of(),
                        Set.of(ExecutionCapability.SANDBOX), Set.of("workspace.write"), true)));
        PolicySnapshot expanded = PolicySnapshot.supersede(
                PolicySnapshotId.generate(), initial,
                PolicySnapshotChangeReason.PLAN_REQUIREMENTS_CHANGED,
                initial.executionPrincipal(), initial.policyPack(), initial.agentProfileId(),
                initial.agentProfileVersion(),
                Set.of(ExecutionCapability.PLAN, ExecutionCapability.STRUCTURED_OUTPUT,
                        ExecutionCapability.SANDBOX),
                Set.of("repository.read", "validation.run", "workspace.write"),
                initial.providerBindingIds(), initial.budget(), fixture.base.owner,
                TaskPlanningFixture.LATER);
        TaskExecution switched = initialized.switchPolicySnapshot(
                expanded, initial, 1, fixture.base.owner, TaskPlanningFixture.LATER);

        assertThrows(
                DomainValidationException.class,
                () -> PlanVersion.publishInitial(
                        PlanVersionId.generate(), fixture.task, initialized, requiresSandbox,
                        List.of(), expanded, overlay, fixture.base.owner, TaskPlanningFixture.LATER));

        PlanVersion published = PlanVersion.publishInitial(
                PlanVersionId.generate(), fixture.task, switched, requiresSandbox,
                List.of(), expanded, overlay, fixture.base.owner, TaskPlanningFixture.LATER);

        assertEquals(expanded.id(), published.policySnapshotId());
    }

    @Test
    void publishesParentedReplacementAndPreservesOldVersion() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        ProposedPlan changed = ProposedPlan.of(
                "# Plan v2\n\nAnalyze, review and validate.",
                List.of(
                        new PlanStep("analyze", 1, "Analyze", PlanStepType.ANALYSIS, Set.of(),
                                Set.of(ExecutionCapability.PLAN), Set.of("repository.read"), true),
                        new PlanStep("validate", 2, "Validate more", PlanStepType.VALIDATION,
                                Set.of("analyze"), Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                                Set.of("validation.run"), true)));

        PlanVersion replacement = PlanVersion.publishReplacement(
                PlanVersionId.generate(), graph.plan(), fixture.task, graph.execution(),
                PlanChangeReason.REVIEW_FEEDBACK, changed, fixture.todo(), graph.policy(),
                graph.overlay(), fixture.base.owner, TaskPlanningFixture.LATER);

        assertEquals(2, replacement.revision());
        assertEquals(graph.plan().id(), replacement.parentVersionId().orElseThrow());
        assertNotEquals(graph.plan().contentHash(), replacement.contentHash());
        assertEquals(1, graph.plan().revision());
    }

    @Test
    void supportsRepublishingSameContentAfterSafetyVersionChange() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        SafetyEnforcementOverlay tightened = graph.overlay().tighten(
                Set.of(SafetyRestriction.RESOURCE_BLOCKED), Set.of(), Set.of(),
                fixture.base.owner, TaskPlanningFixture.LATER);
        TaskExecution current = graph.execution().tightenSafetyOverlay(
                tightened, graph.overlay(), 2, fixture.base.owner, TaskPlanningFixture.LATER);

        PlanVersion replacement = PlanVersion.publishReplacement(
                PlanVersionId.generate(), graph.plan(), fixture.task, current,
                PlanChangeReason.POLICY_CHANGED, fixture.candidate(), fixture.todo(),
                graph.policy(), tightened, fixture.base.owner, TaskPlanningFixture.LATER);

        assertEquals(graph.plan().contentHash(), replacement.contentHash());
        assertEquals(2, replacement.safetyOverlay().version());
        assertTrue(current.planningContext().orElseThrow().currentPlanVersionId().isEmpty());
    }

    @Test
    void rejectsTodoWithMultipleActiveItemsOrUnknownPlanStep() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot policy = fixture.policy();
        SafetyEnforcementOverlay overlay = fixture.overlay();
        List<TodoSummaryItem> invalid = List.of(
                new TodoSummaryItem("One", TodoStatus.IN_PROGRESS, Optional.empty(), Optional.of("analyze")),
                new TodoSummaryItem("Two", TodoStatus.IN_PROGRESS, Optional.empty(), Optional.of("missing")));

        assertThrows(
                DomainValidationException.class,
                () -> PlanVersion.publishInitial(
                        PlanVersionId.generate(), fixture.task,
                        fixture.execution.initializePlanningContext(
                                policy, overlay, 0, fixture.base.owner,
                                TaskPlanningFixture.POLICY_AT),
                        fixture.candidate(), invalid, policy, overlay, fixture.base.owner,
                        TaskPlanningFixture.PLAN_AT));
    }

    @Test
    void rejectsTamperedPublishedPlanHash() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        PlanVersion plan = graph.plan();

        assertThrows(
                DomainValidationException.class,
                () -> PlanVersion.reconstitute(
                        plan.id(), plan.scope(), plan.taskId(), plan.executionId(), plan.revision(),
                        plan.parentVersionId(), plan.changeReason(), plan.policySnapshotId(),
                        plan.policySnapshotHash(), plan.safetyOverlay(), plan.executionPrincipal(),
                        plan.markdown(), plan.contentHash(), plan.steps(), plan.todoSummary(),
                        TaskFactHash.sha256("tampered"), plan.publishedByPrincipalId(),
                        plan.publishedAt()));
    }

    private static PlanVersion publish(
            TaskPlanningFixture fixture,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            ProposedPlan candidate) {
        TaskExecution initialized = fixture.execution.initializePlanningContext(
                policy, overlay, 0, fixture.base.owner, TaskPlanningFixture.POLICY_AT);
        return PlanVersion.publishInitial(
                PlanVersionId.generate(), fixture.task, initialized, candidate,
                List.of(), policy, overlay, fixture.base.owner, TaskPlanningFixture.PLAN_AT);
    }
}
