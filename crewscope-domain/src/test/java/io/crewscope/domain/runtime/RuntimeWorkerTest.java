package io.crewscope.domain.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeWorkerTest {

    @Test
    void registersStableWorkerFactsWithoutMakingItClaimable() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();

        RuntimeWorker worker = fixture.worker(runtime);

        assertEquals(runtime.id(), worker.runtimeId());
        assertEquals(fixture.organizationId, worker.organizationId());
        assertEquals(fixture.environment, worker.environment());
        assertEquals("crewscope-worker-01", worker.stableKey());
        assertEquals(RuntimeProfile.WORKER, worker.profile());
        assertEquals(RuntimeWorkerStatus.REGISTERED, worker.status());
        assertEquals(0, worker.heartbeatSequence());
        assertFalse(worker.canClaim(
                runtime,
                fixture.workerCapabilities,
                RuntimeDomainFixture.CREATED_AT,
                Duration.ofSeconds(30)));
    }

    @Test
    void rejectsWorkerCapabilitiesOutsideItsRuntime() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();
        RuntimeCapabilities unsupported = RuntimeCapabilities.of(
                Set.of(RuntimeCapability.WORKTREE), Set.of("go"), Set.of("gradle"));

        assertThrows(
                DomainValidationException.class,
                () -> RuntimeWorker.register(
                        RuntimeWorkerId.generate(),
                        runtime,
                        "crewscope-worker-02",
                        RuntimeProfile.ALL,
                        unsupported,
                        new RuntimeWorkerCapacity(2, 0),
                        fixture.operator,
                        RuntimeDomainFixture.CREATED_AT));
    }

    @Test
    void activationRecordsHeartbeatWhileDrainAndDisableRemainControlFacts() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        RuntimeWorker registered = fixture.worker(fixture.runtime());

        RuntimeWorker active = registered.activate(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);
        RuntimeWorker draining = active.beginDrain(
                1, fixture.operator, RuntimeDomainFixture.LATER);
        RuntimeWorker disabled = draining.disable(
                2, fixture.operator, RuntimeDomainFixture.LATER);

        assertEquals(RuntimeWorkerStatus.ACTIVE, active.status());
        assertEquals(RuntimeDomainFixture.HEARTBEAT_AT, active.lastHeartbeatAt());
        assertEquals(1, active.heartbeatSequence());
        assertEquals(RuntimeWorkerStatus.DRAINING, draining.status());
        assertEquals(active.lastHeartbeatAt(), draining.lastHeartbeatAt());
        assertEquals(active.heartbeatSequence(), draining.heartbeatSequence());
        assertEquals(RuntimeWorkerStatus.DISABLED, disabled.status());
        assertEquals(3, disabled.version());
    }

    @Test
    void heartbeatPublishesValidatedCapabilitiesCapacityAndSequence() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();
        RuntimeWorker active = fixture.worker(runtime).activate(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);
        RuntimeWorkerCapacity reportedCapacity = new RuntimeWorkerCapacity(8, 3);

        RuntimeWorker heartbeat = active.heartbeat(
                runtime,
                1,
                fixture.workerCapabilities,
                reportedCapacity,
                fixture.operator,
                RuntimeDomainFixture.LATER);

        assertEquals(reportedCapacity, heartbeat.capacity());
        assertEquals(2, heartbeat.heartbeatSequence());
        assertEquals(2, heartbeat.version());
        assertEquals(RuntimeDomainFixture.LATER, heartbeat.lastHeartbeatAt());
    }

    @Test
    void heartbeatRejectsDisabledWorkerAndCapabilityExpansion() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();
        RuntimeWorker disabled = fixture.worker(runtime).disable(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);
        RuntimeCapabilities expanded = RuntimeCapabilities.of(RuntimeCapability.WORKTREE);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> disabled.heartbeat(
                        runtime,
                        1,
                        fixture.workerCapabilities,
                        disabled.capacity(),
                        fixture.operator,
                        RuntimeDomainFixture.LATER));
        RuntimeWorker active = fixture.worker(runtime).activate(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);
        assertThrows(
                DomainValidationException.class,
                () -> active.heartbeat(
                        runtime,
                        1,
                        expanded,
                        active.capacity(),
                        fixture.operator,
                        RuntimeDomainFixture.LATER));
    }

    @Test
    void heartbeatCanReconcileCapabilitiesAfterRuntimeCapabilityReduction() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();
        RuntimeWorker active = fixture.worker(runtime).activate(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);
        RuntimeCapabilities reducedCapabilities = RuntimeCapabilities.of(
                Set.of(RuntimeCapability.CONVERSATION), Set.of("java"), Set.of("maven"));
        ExecutionRuntime reducedRuntime = runtime.publishCapabilities(
                reducedCapabilities, "2.0.1", 0, fixture.operator, RuntimeDomainFixture.LATER);

        assertThrows(
                DomainValidationException.class,
                () -> active.canClaim(
                        reducedRuntime,
                        reducedCapabilities,
                        RuntimeDomainFixture.LATER,
                        Duration.ofMinutes(2)));

        RuntimeWorker reconciled = active.heartbeat(
                reducedRuntime,
                1,
                reducedCapabilities,
                active.capacity(),
                fixture.operator,
                RuntimeDomainFixture.LATER);

        assertEquals(reducedCapabilities, reconciled.capabilities());
        assertTrue(reconciled.canClaim(
                reducedRuntime,
                reducedCapabilities,
                RuntimeDomainFixture.LATER,
                Duration.ofMinutes(2)));
    }

    @Test
    void heartbeatFreshnessIncludesTimeoutBoundaryAndRejectsInvalidClockFacts() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        RuntimeWorker active = fixture.worker(fixture.runtime()).activate(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);

        assertTrue(active.isHeartbeatFresh(
                UtcTimestamp.parse("2026-08-13T08:01:30Z"), Duration.ofSeconds(30)));
        assertFalse(active.isHeartbeatFresh(
                UtcTimestamp.parse("2026-08-13T08:01:30.000001Z"),
                Duration.ofSeconds(30)));
        assertThrows(
                DomainValidationException.class,
                () -> active.isHeartbeatFresh(
                        RuntimeDomainFixture.CREATED_AT, Duration.ofSeconds(30)));
        assertThrows(
                DomainValidationException.class,
                () -> active.isHeartbeatFresh(
                        RuntimeDomainFixture.LATER, Duration.ofSeconds(4)));
    }

    @Test
    void claimRequiresActiveFreshCapableRuntimeWorkerWithCapacity() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();
        RuntimeWorker active = fixture.worker(runtime).activate(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);

        assertTrue(active.canClaim(
                runtime,
                fixture.workerCapabilities,
                RuntimeDomainFixture.LATER,
                Duration.ofMinutes(2)));
        assertFalse(active.canClaim(
                runtime,
                RuntimeCapabilities.of(RuntimeCapability.STREAMING),
                RuntimeDomainFixture.LATER,
                Duration.ofMinutes(2)));
        RuntimeWorker full = active.heartbeat(
                runtime,
                1,
                fixture.workerCapabilities,
                new RuntimeWorkerCapacity(4, 4),
                fixture.operator,
                RuntimeDomainFixture.LATER);
        assertFalse(full.canClaim(
                runtime,
                fixture.workerCapabilities,
                RuntimeDomainFixture.LATER,
                Duration.ofMinutes(2)));
        ExecutionRuntime disabledRuntime = runtime.disable(
                0, fixture.operator, RuntimeDomainFixture.LATER);
        assertFalse(active.canClaim(
                disabledRuntime,
                fixture.workerCapabilities,
                RuntimeDomainFixture.LATER,
                Duration.ofMinutes(2)));
    }

    @Test
    void enforcesCapacityBoundsStableKeyAndTransitionRules() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        RuntimeWorker worker = fixture.worker(fixture.runtime());

        assertThrows(DomainValidationException.class, () -> new RuntimeWorkerCapacity(0, 0));
        assertThrows(DomainValidationException.class, () -> new RuntimeWorkerCapacity(2, 3));
        assertThrows(
                DomainValidationException.class,
                () -> RuntimeWorker.register(
                        RuntimeWorkerId.generate(),
                        fixture.runtime(),
                        "Worker with spaces",
                        RuntimeProfile.WORKER,
                        fixture.workerCapabilities,
                        new RuntimeWorkerCapacity(1, 0),
                        fixture.operator,
                        RuntimeDomainFixture.CREATED_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> worker.beginDrain(
                        0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT));
    }

    @Test
    void rejectsCrossOrganizationEnvironmentRuntimeAndStaleVersion() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();
        RuntimeWorker active = fixture.worker(runtime).activate(
                0, fixture.operator, RuntimeDomainFixture.HEARTBEAT_AT);
        OrganizationId otherOrganizationId = OrganizationId.generate();

        assertThrows(
                OptimisticLockConflictException.class,
                () -> active.beginDrain(0, fixture.operator, RuntimeDomainFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> active.disable(
                        1,
                        fixture.operator(otherOrganizationId, "Foreign operator"),
                        RuntimeDomainFixture.LATER));
        assertThrows(
                DomainValidationException.class,
                () -> active.canClaim(
                        fixture.runtime(otherOrganizationId, fixture.environment),
                        fixture.workerCapabilities,
                        RuntimeDomainFixture.LATER,
                        Duration.ofMinutes(2)));
        assertThrows(
                DomainValidationException.class,
                () -> active.canClaim(
                        fixture.runtime(
                                fixture.organizationId, new RuntimeEnvironment("staging")),
                        fixture.workerCapabilities,
                        RuntimeDomainFixture.LATER,
                        Duration.ofMinutes(2)));
    }

    @Test
    void reconstitutionValidatesHeartbeatInsideAuditLifetime() {
        RuntimeDomainFixture fixture = new RuntimeDomainFixture();
        ExecutionRuntime runtime = fixture.runtime();

        assertThrows(
                DomainValidationException.class,
                () -> RuntimeWorker.reconstitute(
                        RuntimeWorkerId.generate(),
                        fixture.organizationId,
                        fixture.environment,
                        runtime.id(),
                        "crewscope-worker-03",
                        RuntimeProfile.WORKER,
                        fixture.workerCapabilities,
                        new RuntimeWorkerCapacity(2, 0),
                        RuntimeWorkerStatus.ACTIVE,
                        RuntimeDomainFixture.LATER,
                        1,
                        1,
                        AuditMetadata.createdBy(
                                fixture.operator.id(), RuntimeDomainFixture.CREATED_AT)));
    }
}
