package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
