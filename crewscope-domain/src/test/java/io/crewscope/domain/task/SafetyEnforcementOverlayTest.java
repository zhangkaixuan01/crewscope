package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.ResolvedAgentExecutionTestFixture;
import io.crewscope.domain.agent.ResolvedModelRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SafetyEnforcementOverlayTest {

    @Test
    void tightensMonotonicallyAndImmediatelyBlocksPolicyCapabilitiesAndTools() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        PolicySnapshot policy = fixture.policy();
        SafetyEnforcementOverlay initial = fixture.overlay();

        SafetyEnforcementOverlay tightened = initial.tighten(
                Set.of(SafetyRestriction.TOOL_DISABLED),
                Set.of(ExecutionCapability.STRUCTURED_OUTPUT),
                Set.of("validation.run"),
                fixture.base.owner,
                TaskPlanningFixture.LATER);

        assertEquals(initial.id(), tightened.id());
        assertEquals(2, tightened.version());
        assertEquals(initial.overlayHash(), tightened.parentOverlayHash().orElseThrow());
        assertTrue(initial.permits(
                policy, Set.of(ExecutionCapability.STRUCTURED_OUTPUT), Set.of("validation.run")));
        assertFalse(tightened.permits(
                policy, Set.of(ExecutionCapability.STRUCTURED_OUTPUT), Set.of("validation.run")));
        assertTrue(initial.disabledTools().isEmpty());
    }

    @Test
    void rejectsNoopTighteningAndTamperedHash() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        SafetyEnforcementOverlay overlay = fixture.overlay();

        assertThrows(
                DomainValidationException.class,
                () -> overlay.tighten(
                        Set.of(), Set.of(), Set.of(), fixture.base.owner,
                        TaskPlanningFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> SafetyEnforcementOverlay.reconstitute(
                        overlay.id(), overlay.scope(), overlay.taskId(), overlay.executionId(),
                        overlay.version(), Optional.empty(), overlay.restrictions(), overlay.disabledCapabilities(),
                        overlay.disabledTools(), TaskFactHash.sha256("tampered"),
                        overlay.createdByPrincipalId(), overlay.createdAt()));
    }

    @Test
    void modelSafetyOverlayCanOnlyDisableModelsAlreadyFixedBySchemaV2() {
        TaskPlanningFixture fixture = new TaskPlanningFixture();
        ResolvedAgentExecutionConfiguration resolved =
                ResolvedAgentExecutionTestFixture.create();
        PolicySnapshot policy = PolicySnapshot.initialV2(
                PolicySnapshotId.generate(),
                fixture.task,
                fixture.execution,
                fixture.base.executor,
                resolved,
                Set.of(ExecutionCapability.PLAN),
                Set.of("repository.read"),
                Set.of(),
                new PolicyBudget(10_000, 4, 4, 600),
                fixture.base.owner,
                TaskPlanningFixture.POLICY_AT);
        SafetyEnforcementOverlay initial = fixture.overlay();

        assertTrue(initial.permitsModelInvocation(policy, ResolvedModelRole.PRIMARY));
        assertFalse(initial.permitsModelInvocation(policy, ResolvedModelRole.FALLBACK));

        SafetyEnforcementOverlay tightened = initial.tighten(
                Set.of(SafetyRestriction.MODEL_DISABLED),
                Set.of(),
                Set.of(),
                fixture.base.owner,
                TaskPlanningFixture.LATER);
        assertFalse(tightened.permitsModelInvocation(policy, ResolvedModelRole.PRIMARY));
        assertEquals(resolved, policy.agentExecutionConfiguration().orElseThrow());
    }
}
