package io.crewscope.domain.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionRuntimeTest {

    @Test
    void registersAnActiveOrganizationAndEnvironmentScopedRuntime() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();

        ExecutionRuntime runtime = fixture.runtime();

        assertEquals(fixture.organizationId, runtime.organizationId());
        assertEquals(fixture.environment, runtime.environment());
        assertEquals("agentscope-java", runtime.key());
        assertEquals("2.0.0", runtime.implementationVersion());
        assertEquals(ExecutionRuntimeStatus.ACTIVE, runtime.status());
        assertEquals(0, runtime.version());
        assertEquals(fixture.operator.id(), runtime.audit().createdBy().orElseThrow());
    }

    @Test
    void matchesFeaturesLanguagesAndBuildSystemsAsOneCapabilitySnapshot() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();

        assertTrue(runtime.supports(fixture.workerCapabilities));
        assertFalse(runtime.supports(RuntimeCapabilities.of(
                Set.of(RuntimeCapability.WORKTREE), Set.of("java"), Set.of("maven"))));
        assertFalse(runtime.supports(RuntimeCapabilities.of(
                Set.of(RuntimeCapability.PLAN), Set.of("python"), Set.of("maven"))));
    }

    @Test
    void publishesChangedCapabilitiesAndImplementationVersion() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        RuntimeCapabilities replacement = RuntimeCapabilities.of(
                Set.of(RuntimeCapability.CONVERSATION), Set.of("java"), Set.of("maven"));

        ExecutionRuntime changed = fixture.runtime().publishCapabilities(
                replacement, "2.0.1-beta.1", 0, fixture.operator, RuntimeDomainFixture.LATER);

        assertEquals(replacement, changed.capabilities());
        assertEquals("2.0.1-beta.1", changed.implementationVersion());
        assertEquals(1, changed.version());
        assertEquals(fixture.operator.id(), changed.audit().updatedBy().orElseThrow());
    }

    @Test
    void rejectsNoOpCapabilityPublicationAndMalformedStableCoordinates() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();

        assertThrows(
                DomainValidationException.class,
                () -> runtime.publishCapabilities(
                        runtime.capabilities(),
                        runtime.implementationVersion(),
                        0,
                        fixture.operator,
                        RuntimeDomainFixture.LATER));
        assertThrows(DomainValidationException.class, () -> new RuntimeEnvironment("Prod US"));
        assertEquals("a", new RuntimeEnvironment("a").value());
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionRuntime.register(
                        ExecutionRuntimeId.generate(),
                        fixture.organizationId,
                        fixture.environment,
                        "Invalid_Key",
                        "Invalid runtime",
                        "version-two",
                        fixture.runtimeCapabilities,
                        fixture.operator,
                        RuntimeDomainFixture.CREATED_AT));
    }

    @Test
    void supportsDisableActivateAndTerminalArchiveLifecycle() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();

        ExecutionRuntime disabled = fixture.runtime().disable(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);
        ExecutionRuntime active = disabled.activate(
                1, fixture.operator, RuntimeDomainFixture.LATER);
        ExecutionRuntime archived = active.archive(
                2, fixture.operator, RuntimeDomainFixture.LATER);

        assertEquals(ExecutionRuntimeStatus.DISABLED, disabled.status());
        assertFalse(disabled.supports(fixture.workerCapabilities));
        assertEquals(ExecutionRuntimeStatus.ACTIVE, active.status());
        assertEquals(ExecutionRuntimeStatus.ARCHIVED, archived.status());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.activate(3, fixture.operator, RuntimeDomainFixture.LATER));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.publishCapabilities(
                        fixture.runtimeCapabilities,
                        "2.0.1",
                        3,
                        fixture.operator,
                        RuntimeDomainFixture.LATER));
    }

    @Test
    void enforcesOptimisticLockAndOrganizationActorBoundary() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();

        assertThrows(
                OptimisticLockConflictException.class,
                () -> runtime.disable(1, fixture.operator, RuntimeDomainFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> runtime.disable(
                        0,
                        fixture.operator(OrganizationId.generate(), "Foreign operator"),
                        RuntimeDomainFixture.LATER));
    }
}
