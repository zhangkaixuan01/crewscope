package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskExecutionPlanningContextTest {

    @Test
    void initializesAndSwitchesCurrentPublishedPlanWithVersionGuards() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot policy = fixture.policy();
        SafetyEnforcementOverlay overlay = fixture.overlay();
        TaskExecution initialized = fixture.execution.initializePlanningContext(
                policy, overlay, 0, fixture.base.owner, TaskPlanningFixture.POLICY_AT);
        PlanVersion plan = PlanVersion.publishInitial(
                PlanVersionId.generate(), fixture.task, initialized, fixture.candidate(),
                fixture.todo(), policy, overlay, fixture.base.owner, TaskPlanningFixture.PLAN_AT);

        TaskExecution selected = initialized.switchCurrentPlan(
                plan, Optional.empty(), 1, fixture.base.owner, TaskPlanningFixture.PLAN_AT);

        assertEquals(Optional.of(plan.id()),
                selected.planningContext().orElseThrow().currentPlanVersionId());
        assertEquals(2, selected.version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> selected.switchCurrentPlan(
                        plan, Optional.of(plan.id()), 1, fixture.base.owner,
                        TaskPlanningFixture.LATER));
    }

    @Test
    void policySwitchRequiresImmediateParentAndClearsCurrentPlan() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        PolicySnapshot next = PolicySnapshot.supersede(
                PolicySnapshotId.generate(), graph.policy(),
                PolicySnapshotChangeReason.PLAN_REQUIREMENTS_CHANGED,
                graph.policy().executionPrincipal(), graph.policy().policyPack(),
                graph.policy().agentProfileId(), graph.policy().agentProfileVersion(),
                Set.of(ExecutionCapability.PLAN, ExecutionCapability.STRUCTURED_OUTPUT,
                        ExecutionCapability.SANDBOX),
                Set.of("repository.read", "validation.run", "workspace.write"),
                graph.policy().providerBindingIds(), graph.policy().budget(),
                fixture.base.owner, TaskPlanningFixture.LATER);

        TaskExecution switched = graph.execution().switchPolicySnapshot(
                next, graph.policy(), 2, fixture.base.owner, TaskPlanningFixture.LATER);

        assertEquals(next.id(), switched.planningContext().orElseThrow().policySnapshotId());
        assertTrue(switched.planningContext().orElseThrow().currentPlanVersionId().isEmpty());
        assertThrows(
                DomainValidationException.class,
                () -> switched.switchPolicySnapshot(
                        next, graph.policy(), 3, fixture.base.owner, TaskPlanningFixture.LATER));
    }

    @Test
    void overlaySwitchRequiresExactParentAndClearsCurrentPlan() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        TaskPlanningFixture.PlanningGraph graph = fixture.graph();
        SafetyEnforcementOverlay tightened = graph.overlay().tighten(
                Set.of(SafetyRestriction.TOOL_DISABLED), Set.of(), Set.of("validation.run"),
                fixture.base.owner, TaskPlanningFixture.LATER);
        SafetyEnforcementOverlay sibling = graph.overlay().tighten(
                Set.of(SafetyRestriction.RESOURCE_BLOCKED), Set.of(), Set.of(),
                fixture.base.owner, TaskPlanningFixture.LATER);

        TaskExecution switched = graph.execution().tightenSafetyOverlay(
                tightened, graph.overlay(), 2, fixture.base.owner, TaskPlanningFixture.LATER);

        assertEquals(tightened.reference(),
                switched.planningContext().orElseThrow().safetyOverlay());
        assertTrue(switched.planningContext().orElseThrow().currentPlanVersionId().isEmpty());
        assertThrows(
                DomainValidationException.class,
                () -> graph.execution().tightenSafetyOverlay(
                        sibling, tightened, 2, fixture.base.owner, TaskPlanningFixture.LATER));
    }
}
