package io.crewscope.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.runtime.ExecutionRuntimeId;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.runtime.RuntimeWorkerId;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemScope;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionLeaseTest {

    @Test
    void acquiresPrepareLeaseForCompleteClaimableLineage() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();

        ExecutionLease lease = fixture.lease(claimed);

        assertEquals(claimed.scope().organizationId(), lease.organizationId());
        assertEquals(fixture.environment, lease.environment());
        assertEquals(claimed.id(), lease.taskExecutionId());
        assertEquals(claimed.attempt(), lease.attempt());
        assertEquals(fixture.runtime.id(), lease.runtimeId());
        assertEquals(fixture.worker.id(), lease.workerId());
        assertEquals(ExecutionLeasePhase.PREPARE, lease.phase());
        assertEquals(0, lease.version());
        assertTrue(lease.release().isEmpty());
    }

    @Test
    void returnsOneTimeReceiptWithoutDisclosingClaimSecretInLogs() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        ExecutionLease lease = fixture.lease(claimed);

        ClaimReceipt receipt = lease.receipt(ExecutionLeaseDomainFixture.CLAIM_TOKEN, claimed);

        assertEquals(lease.id(), receipt.leaseId());
        assertEquals(lease.claimTokenHash(), receipt.ownership().claimTokenHash());
        assertEquals(claimed.version(), receipt.taskExecutionVersion());
        assertEquals(lease.version(), receipt.leaseVersion());
        assertFalse(receipt.toString().contains(
                ExecutionLeaseDomainFixture.CLAIM_TOKEN.reveal()));
        assertTrue(receipt.toString().contains("[REDACTED]"));
        assertThrows(
                DomainValidationException.class,
                () -> lease.receipt(
                        new ClaimToken("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"),
                        claimed));
        assertThrows(
                DomainValidationException.class,
                () -> new ClaimReceipt(
                        lease.id(), claimed.id(), claimed.attempt(), lease.runtimeId(),
                        lease.workerId(), ExecutionLeaseDomainFixture.CLAIM_TOKEN,
                        lease.fencingToken(), -1, lease.version(), lease.expiresAt()));
        assertThrows(
                DomainValidationException.class,
                () -> lease.receipt(
                        ExecutionLeaseDomainFixture.CLAIM_TOKEN,
                        fixture.runningExecution(claimed)));
    }

    @Test
    void switchesPrepareLeaseToRunOnlyAfterExecutionStarts() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        ExecutionLease lease = fixture.lease(claimed);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> lease.beginRun(
                        claimed,
                        fixture.ownership(claimed),
                        0,
                        ExecutionLeaseDomainFixture.RUN_AT,
                        ExecutionLeaseDomainFixture.RUN_EXPIRY));

        TaskExecution running = fixture.runningExecution(claimed);
        ExecutionLease runLease = lease.beginRun(
                running,
                fixture.ownership(claimed),
                0,
                ExecutionLeaseDomainFixture.RUN_AT,
                ExecutionLeaseDomainFixture.RUN_EXPIRY);

        assertEquals(ExecutionLeasePhase.RUN, runLease.phase());
        assertEquals(1, runLease.version());
        assertEquals(ExecutionLeaseDomainFixture.RUN_AT, runLease.lastHeartbeatAt());
    }

    @Test
    void heartbeatRenewsLeaseVersionWithoutChangingOwnershipOrTaskVersion() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        ExecutionLease lease = fixture.lease(claimed);

        ExecutionLease renewed = lease.heartbeat(
                fixture.ownership(claimed),
                0,
                ExecutionLeaseDomainFixture.PREPARE_AT,
                UtcTimestamp.parse("2026-08-13T08:16:00Z"));

        assertEquals(1, renewed.version());
        assertEquals(ExecutionLeaseDomainFixture.PREPARE_AT, renewed.lastHeartbeatAt());
        assertEquals(lease.runtimeId(), renewed.runtimeId());
        assertEquals(lease.workerId(), renewed.workerId());
        assertEquals(lease.claimTokenHash(), renewed.claimTokenHash());
        assertEquals(lease.fencingToken(), renewed.fencingToken());
        assertEquals(2, claimed.version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> renewed.heartbeat(
                        fixture.ownership(claimed),
                        0,
                        ExecutionLeaseDomainFixture.RUN_AT,
                        ExecutionLeaseDomainFixture.RUN_EXPIRY));
    }

    @Test
    void enforcesPrepareAndRunDurationBounds() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();

        assertThrows(
                DomainValidationException.class,
                () -> ExecutionLease.acquire(
                        ExecutionLeaseId.generate(),
                        claimed,
                        fixture.runtime,
                        fixture.worker,
                        fixture.capabilities,
                        Duration.ofMinutes(2),
                        ExecutionLeaseDomainFixture.CLAIM_TOKEN,
                        ExecutionLeaseDomainFixture.CLAIM_AT,
                        UtcTimestamp.parse("2026-08-13T08:10:04Z")));
        ExecutionLease prepareLease = fixture.lease(claimed);
        TaskExecution running = fixture.runningExecution(claimed);
        assertThrows(
                DomainValidationException.class,
                () -> prepareLease.beginRun(
                        running,
                        fixture.ownership(claimed),
                        0,
                        ExecutionLeaseDomainFixture.RUN_AT,
                        UtcTimestamp.parse("2026-08-13T08:22:01Z")));
    }

    @Test
    void rejectsWrongAttemptWorkerRuntimeTokenAndFencingCoordinates() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        ExecutionLease lease = fixture.lease(claimed);
        LeaseOwnership current = fixture.ownership(claimed);

        LeaseOwnership[] invalid = {
            new LeaseOwnership(
                    TaskExecutionId.generate(), current.attempt(), current.runtimeId(),
                    current.workerId(), current.claimTokenHash(), current.fencingToken()),
            new LeaseOwnership(
                    current.taskExecutionId(), current.attempt() + 1, current.runtimeId(),
                    current.workerId(), current.claimTokenHash(), current.fencingToken()),
            new LeaseOwnership(
                    current.taskExecutionId(), current.attempt(), ExecutionRuntimeId.generate(),
                    current.workerId(), current.claimTokenHash(), current.fencingToken()),
            new LeaseOwnership(
                    current.taskExecutionId(), current.attempt(), current.runtimeId(),
                    RuntimeWorkerId.generate(), current.claimTokenHash(), current.fencingToken()),
            new LeaseOwnership(
                    current.taskExecutionId(), current.attempt(), current.runtimeId(),
                    current.workerId(), new ClaimTokenHash("0".repeat(64)), current.fencingToken()),
            new LeaseOwnership(
                    current.taskExecutionId(), current.attempt(), current.runtimeId(),
                    current.workerId(), current.claimTokenHash(), current.fencingToken().next())
        };

        for (LeaseOwnership ownership : invalid) {
            assertThrows(
                    DomainValidationException.class,
                    () -> lease.heartbeat(
                            ownership,
                            0,
                            ExecutionLeaseDomainFixture.PREPARE_AT,
                            UtcTimestamp.parse("2026-08-13T08:16:00Z")));
        }
    }

    @Test
    void acquisitionCopiesTheTaskExecutionCurrentFencingEpoch() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution firstClaim = fixture.claimedExecution();
        TaskExecution reclaimed = firstClaim
                .beginRecovery(2, fixture.taskFixture.executor, ExecutionLeaseDomainFixture.PREPARE_AT)
                .requeue(
                        ExecutionLeaseDomainFixture.RUN_AT,
                        3,
                        fixture.taskFixture.executor,
                        ExecutionLeaseDomainFixture.RUN_AT)
                .claim(4, fixture.taskFixture.executor, ExecutionLeaseDomainFixture.RUN_AT);

        ExecutionLease lease = ExecutionLease.acquire(
                ExecutionLeaseId.generate(),
                reclaimed,
                fixture.runtime,
                fixture.worker,
                fixture.capabilities,
                Duration.ofMinutes(2),
                ExecutionLeaseDomainFixture.CLAIM_TOKEN,
                ExecutionLeaseDomainFixture.RUN_AT,
                UtcTimestamp.parse("2026-08-13T08:17:00Z"));

        assertEquals(reclaimed.lastFencingToken().orElseThrow(), lease.fencingToken());
    }

    @Test
    void rejectsCrossOrganizationExecutionAndUnavailableRuntimeCapability() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        WorkItemScope foreignScope = new WorkItemScope(
                OrganizationId.generate(),
                claimed.scope().teamId(),
                claimed.scope().workspaceId(),
                claimed.scope().projectId());
        TaskExecution foreignExecution = TaskExecution.reconstitute(
                TaskExecutionId.generate(),
                foreignScope,
                claimed.taskId(),
                claimed.attempt(),
                claimed.maxAttempts(),
                claimed.parentExecutionId(),
                claimed.priority(),
                claimed.notBefore(),
                TaskExecutionStatus.CLAIMED,
                claimed.waiting(),
                claimed.controlRequest(),
                claimed.terminal(),
                claimed.planningContext(),
                claimed.lastFencingToken(),
                claimed.version(),
                claimed.audit());

        assertThrows(
                DomainValidationException.class,
                () -> fixture.lease(foreignExecution));
        assertThrows(
                DomainValidationException.class,
                () -> ExecutionLease.acquire(
                        ExecutionLeaseId.generate(),
                        claimed,
                        fixture.runtime,
                        fixture.worker,
                        io.crewscope.domain.runtime.RuntimeCapabilities.of(
                                Set.of(RuntimeCapability.WORKTREE), Set.of(), Set.of()),
                        Duration.ofMinutes(2),
                        ExecutionLeaseDomainFixture.CLAIM_TOKEN,
                        ExecutionLeaseDomainFixture.CLAIM_AT,
                        ExecutionLeaseDomainFixture.PREPARE_EXPIRY));
    }

    @Test
    void rejectsImpossibleReconstitutedTimelines() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        ExecutionLease lease = fixture.lease(claimed);

        assertThrows(
                DomainValidationException.class,
                () -> reconstituteTimeline(
                        lease,
                        UtcTimestamp.parse("2026-08-13T08:09:59Z"),
                        lease.expiresAt(),
                        java.util.Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstituteTimeline(
                        lease,
                        lease.lastHeartbeatAt(),
                        lease.lastHeartbeatAt(),
                        java.util.Optional.empty()));
        assertThrows(
                DomainValidationException.class,
                () -> reconstituteTimeline(
                        lease,
                        lease.lastHeartbeatAt(),
                        lease.expiresAt(),
                        java.util.Optional.of(new ExecutionLeaseRelease(
                                ExecutionLeaseReleaseReason.EXPIRED,
                                UtcTimestamp.parse("2026-08-13T08:14:59Z")))));
    }

    @Test
    void expiresAtExactBoundaryAndRejectsStaleHeartbeat() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        ExecutionLease lease = fixture.lease(claimed);

        assertFalse(lease.isExpired(UtcTimestamp.parse("2026-08-13T08:14:59.999999Z")));
        assertTrue(lease.isExpired(ExecutionLeaseDomainFixture.PREPARE_EXPIRY));
        ExecutionLease expired = lease.expire(0, ExecutionLeaseDomainFixture.PREPARE_EXPIRY);

        assertEquals(
                ExecutionLeaseReleaseReason.EXPIRED,
                expired.release().orElseThrow().reason());
        assertFalse(expired.isActiveAt(ExecutionLeaseDomainFixture.PREPARE_EXPIRY));
        assertThrows(
                DomainValidationException.class,
                () -> lease.heartbeat(
                        fixture.ownership(claimed),
                        0,
                        ExecutionLeaseDomainFixture.PREPARE_EXPIRY,
                        UtcTimestamp.parse("2026-08-13T08:16:00Z")));
    }

    @Test
    void releasesOnlyForMatchingExecutionOutcome() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        TaskExecution running = fixture.runningExecution(claimed);
        ExecutionLease runLease = fixture.lease(claimed).beginRun(
                running,
                fixture.ownership(claimed),
                0,
                ExecutionLeaseDomainFixture.RUN_AT,
                UtcTimestamp.parse("2026-08-13T08:21:00Z"));
        TaskExecution completed = running.complete(
                4, fixture.taskFixture.executor, ExecutionLeaseDomainFixture.FINISH_AT);

        assertThrows(
                DomainValidationException.class,
                () -> runLease.release(
                        running,
                        fixture.ownership(claimed),
                        ExecutionLeaseReleaseReason.COMPLETED,
                        1,
                        ExecutionLeaseDomainFixture.FINISH_AT));

        TaskExecution wrongEpoch = TaskExecution.reconstitute(
                completed.id(), completed.scope(), completed.taskId(), completed.attempt(),
                completed.maxAttempts(), completed.parentExecutionId(), completed.priority(),
                completed.notBefore(), completed.status(), completed.waiting(),
                completed.controlRequest(), completed.terminal(), completed.planningContext(),
                java.util.Optional.of(completed.lastFencingToken().orElseThrow().next()),
                completed.version(), completed.audit());
        assertThrows(
                DomainValidationException.class,
                () -> runLease.release(
                        wrongEpoch,
                        fixture.ownership(claimed),
                        ExecutionLeaseReleaseReason.COMPLETED,
                        1,
                        ExecutionLeaseDomainFixture.FINISH_AT));

        ExecutionLease released = runLease.release(
                completed,
                fixture.ownership(claimed),
                ExecutionLeaseReleaseReason.COMPLETED,
                1,
                ExecutionLeaseDomainFixture.FINISH_AT);

        assertEquals(
                ExecutionLeaseReleaseReason.COMPLETED,
                released.release().orElseThrow().reason());
        assertFalse(released.isActiveAt(ExecutionLeaseDomainFixture.FINISH_AT));
    }

    @Test
    void releasedAndExpiredTerminalFactsAreMutuallyExclusive() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        TaskExecution running = fixture.runningExecution(claimed);
        ExecutionLease runLease = fixture.lease(claimed).beginRun(
                running,
                fixture.ownership(claimed),
                0,
                ExecutionLeaseDomainFixture.RUN_AT,
                UtcTimestamp.parse("2026-08-13T08:21:00Z"));
        TaskExecution completed = running.complete(
                4, fixture.taskFixture.executor, ExecutionLeaseDomainFixture.FINISH_AT);
        ExecutionLease released = runLease.release(
                completed,
                fixture.ownership(claimed),
                ExecutionLeaseReleaseReason.COMPLETED,
                1,
                ExecutionLeaseDomainFixture.FINISH_AT);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> released.release(
                        completed,
                        fixture.ownership(claimed),
                        ExecutionLeaseReleaseReason.COMPLETED,
                        2,
                        ExecutionLeaseDomainFixture.FINISH_AT));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> released.expire(
                        2, UtcTimestamp.parse("2026-08-13T08:22:00Z")));
    }

    @Test
    void oldFencingEpochCannotOwnAReclaimedLease() {
        ExecutionLeaseDomainFixture fixture = new ExecutionLeaseDomainFixture();
        TaskExecution claimed = fixture.claimedExecution();
        ExecutionLease firstLease = fixture.lease(claimed);
        ExecutionLease expired = firstLease.expire(
                0, ExecutionLeaseDomainFixture.PREPARE_EXPIRY);
        FencingToken nextEpoch = expired.fencingToken().next();
        ExecutionLease reclaimed = ExecutionLease.reconstitute(
                ExecutionLeaseId.generate(),
                expired.organizationId(),
                expired.environment(),
                expired.taskExecutionId(),
                expired.attempt(),
                expired.runtimeId(),
                RuntimeWorkerId.generate(),
                new ClaimToken("0123456789abcdefghijklmnopqrstuvwxyzABCDEFG").hash(),
                nextEpoch,
                ExecutionLeasePhase.PREPARE,
                UtcTimestamp.parse("2026-08-13T08:16:00Z"),
                UtcTimestamp.parse("2026-08-13T08:16:00Z"),
                UtcTimestamp.parse("2026-08-13T08:20:00Z"),
                java.util.Optional.empty(),
                0);

        assertTrue(nextEpoch.compareTo(firstLease.fencingToken()) > 0);
        assertFalse(reclaimed.owns(
                fixture.ownership(claimed), UtcTimestamp.parse("2026-08-13T08:17:00Z")));
        assertNotEquals(firstLease.id(), reclaimed.id());
    }

    private static ExecutionLease reconstituteTimeline(
            ExecutionLease source,
            UtcTimestamp lastHeartbeatAt,
            UtcTimestamp expiresAt,
            java.util.Optional<ExecutionLeaseRelease> release) {
        return ExecutionLease.reconstitute(
                source.id(), source.organizationId(), source.environment(), source.taskExecutionId(),
                source.attempt(), source.runtimeId(), source.workerId(), source.claimTokenHash(),
                source.fencingToken(), source.phase(), source.acquiredAt(), lastHeartbeatAt,
                expiresAt, release, source.version());
    }
}
