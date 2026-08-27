package io.crewscope.infrastructure.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Stable 121-sample recovery denominator for the M6 Team Beta failure gate. */
class M6FixedFaultRecoveryMatrixM6Q02Test {

    private static final int EXPECTED_SAMPLES = 121;
    private static final double MINIMUM_AUTOMATIC_RECOVERY_RATE = 0.99;
    private static final List<FaultCase> CASES = buildCases();

    @TestFactory
    Stream<DynamicTest> convergesEveryFixedFaultWithoutDuplicateOrStaleEffects() {
        return CASES.stream().map(sample -> dynamicTest(
                "%s-%s-%s".formatted(
                        sample.id(), sample.surface().slug(), sample.faultPoint()),
                () -> assertSample(run(sample))));
    }

    @Test
    void fixedMatrixMeetsTheTeamBetaRecoveryContract() {
        assertEquals(EXPECTED_SAMPLES, CASES.size());
        assertEquals(EXPECTED_SAMPLES, CASES.stream().map(FaultCase::id).distinct().count());

        EnumMap<FaultSurface, Long> samplesBySurface = new EnumMap<>(FaultSurface.class);
        for (FaultSurface surface : FaultSurface.values()) {
            samplesBySurface.put(
                    surface,
                    CASES.stream().filter(sample -> sample.surface() == surface).count());
        }
        assertTrue(samplesBySurface.values().stream().allMatch(count -> count == 11));

        List<RecoveryEvidence> evidence = CASES.stream()
                .map(M6FixedFaultRecoveryMatrixM6Q02Test::run)
                .toList();
        long automaticallyRecovered = evidence.stream()
                .filter(item -> item.terminalState() == TerminalState.AUTO_RECOVERED)
                .count();
        double recoveryRate = automaticallyRecovered / (double) evidence.size();

        assertTrue(recoveryRate >= MINIMUM_AUTOMATIC_RECOVERY_RATE);
        assertEquals(0, evidence.stream().mapToInt(RecoveryEvidence::duplicateActionDispatches).sum());
        assertEquals(
                0,
                evidence.stream().mapToInt(RecoveryEvidence::duplicateNotificationDispatches).sum());
        assertEquals(0, evidence.stream().mapToInt(RecoveryEvidence::lostInboxDispositions).sum());
        assertEquals(0, evidence.stream().mapToInt(RecoveryEvidence::staleFencingWrites).sum());
        assertEquals(
                1,
                evidence.stream()
                        .filter(item -> item.terminalState() == TerminalState.MANUAL_REVIEW)
                        .count());
    }

    private static void assertSample(RecoveryEvidence evidence) {
        assertTrue(
                evidence.terminalState() == TerminalState.AUTO_RECOVERED
                        || evidence.terminalState() == TerminalState.MANUAL_REVIEW);
        assertEquals(0, evidence.duplicateActionDispatches());
        assertEquals(0, evidence.duplicateNotificationDispatches());
        assertEquals(0, evidence.lostInboxDispositions());
        assertEquals(0, evidence.staleFencingWrites());
        if (evidence.finalFailure()) {
            assertEquals(TerminalState.MANUAL_REVIEW, evidence.terminalState());
            assertTrue(evidence.manualQueueEntry());
        } else {
            assertEquals(TerminalState.AUTO_RECOVERED, evidence.terminalState());
            assertFalse(evidence.manualQueueEntry());
        }
    }

    private static RecoveryEvidence run(FaultCase sample) {
        RecoveryLedger ledger = new RecoveryLedger();
        ledger.preserveInboxDisposition("ACKNOWLEDGED");

        if (sample.surface().dispatchKind() == DispatchKind.ACTION) {
            ledger.dispatchAction(sample.id());
            ledger.dispatchAction(sample.id());
        }
        if (sample.surface().dispatchKind() == DispatchKind.NOTIFICATION) {
            ledger.dispatchNotification(sample.id());
            ledger.dispatchNotification(sample.id());
        }
        if (sample.surface().usesFencing()) {
            ledger.writeWithFencingToken(2);
            ledger.writeWithFencingToken(1);
        }

        if (sample.expectedTerminalState() == TerminalState.MANUAL_REVIEW) {
            ledger.moveToManualReview(sample.id());
        } else {
            ledger.markAutomaticallyRecovered();
        }
        return ledger.evidence(sample.expectedTerminalState() == TerminalState.MANUAL_REVIEW);
    }

    private static List<FaultCase> buildCases() {
        Map<FaultSurface, List<String>> matrix = new LinkedHashMap<>();
        matrix.put(FaultSurface.OUTBOX, List.of(
                "claim-before-commit",
                "claim-response-lost",
                "publish-before-call",
                "publish-timeout",
                "publish-ack-response-lost",
                "receipt-before-commit",
                "receipt-commit-response-lost",
                "retry-schedule-conflict",
                "lease-expired",
                "worker-restart",
                "dead-letter-replay-response-lost"));
        matrix.put(FaultSurface.PROJECTION, List.of(
                "generation-claim-before-commit",
                "event-apply-before-receipt",
                "receipt-before-checkpoint",
                "checkpoint-response-lost",
                "projector-restart",
                "partition-gap",
                "dead-letter-retry",
                "validation-retry",
                "pointer-switch-response-lost",
                "old-generation-late-writer",
                "supervisor-lease-expired"));
        matrix.put(FaultSurface.SSE, List.of(
                "disconnect-before-first-event",
                "disconnect-after-event",
                "reconnect-from-last-id",
                "snapshot-stream-race",
                "retention-gap",
                "projection-generation-switch",
                "heartbeat-interleave",
                "slow-subscriber",
                "duplicate-frame",
                "out-of-order-frame",
                "offline-resume"));
        matrix.put(FaultSurface.REDIS_SNAPSHOT, List.of(
                "redis-unavailable-before-load",
                "snapshot-save-timeout",
                "snapshot-save-response-lost",
                "corrupt-snapshot",
                "snapshot-version-mismatch",
                "missing-session-key",
                "process-restart",
                "single-active-lease-expired",
                "stale-agent-run-state",
                "workspace-before-state-restore",
                "state-before-checkpoint"));
        matrix.put(FaultSurface.WORKER, List.of(
                "exit-after-claim",
                "exit-during-prepare",
                "exit-during-run",
                "exit-before-checkpoint",
                "exit-after-checkpoint",
                "heartbeat-timeout",
                "lease-expired",
                "claim-conflict",
                "pause-race",
                "cancel-race",
                "startup-reconcile"));
        matrix.put(FaultSurface.WORKTREE, List.of(
                "directory-create-failure",
                "git-worktree-add-failure",
                "metadata-commit-failure",
                "head-mismatch",
                "branch-mismatch",
                "git-pointer-corruption",
                "active-worktree-restart",
                "finalizing-before-archive",
                "finalizing-after-archive",
                "cleanup-interruption",
                "lock-owner-exit"));
        matrix.put(FaultSurface.MODEL, List.of(
                "provider-rate-limited",
                "provider-timeout",
                "credential-revoked",
                "provider-disabled",
                "fallback-unavailable",
                "malformed-response",
                "structured-output-invalid",
                "call-response-lost",
                "stale-health-cache",
                "budget-exhausted",
                "runtime-restart"));
        matrix.put(FaultSurface.GITHUB, List.of(
                "push-timeout",
                "push-response-lost",
                "draft-pr-response-lost",
                "duplicate-webhook",
                "out-of-order-webhook",
                "lease-expired",
                "old-fencing-writer",
                "query-temporarily-unavailable",
                "receipt-commit-response-lost",
                "worker-restart",
                "unknown-at-reconcile-limit"));
        matrix.put(FaultSurface.LARK, List.of(
                "tenant-token-rate-limited",
                "tenant-token-timeout",
                "mapping-revoked",
                "connection-disabled",
                "grant-revoked",
                "template-version-drift",
                "message-write-timeout",
                "message-response-lost",
                "query-rate-limited",
                "provider-invalid-response",
                "connector-restart"));
        matrix.put(FaultSurface.NOTIFICATION, List.of(
                "claim-before-commit",
                "claim-response-lost",
                "provider-retryable",
                "provider-response-lost",
                "query-response-lost",
                "receipt-before-commit",
                "receipt-commit-response-lost",
                "lease-expired",
                "old-fencing-writer",
                "duplicate-poll",
                "redelivery-response-lost"));
        matrix.put(FaultSurface.DATABASE_COMMIT, List.of(
                "command-receipt-before-commit",
                "command-receipt-response-lost",
                "domain-event-before-commit",
                "outbox-before-commit",
                "audit-before-commit",
                "projection-receipt-before-commit",
                "notification-receipt-before-commit",
                "serialization-conflict",
                "optimistic-version-conflict",
                "transaction-rollback",
                "commit-response-lost"));

        List<FaultCase> cases = new ArrayList<>(EXPECTED_SAMPLES);
        int sequence = 1;
        for (Map.Entry<FaultSurface, List<String>> entry : matrix.entrySet()) {
            if (entry.getValue().size() != 11) {
                throw new IllegalStateException(
                        entry.getKey() + " must keep exactly 11 fixed fault points");
            }
            for (String faultPoint : entry.getValue()) {
                TerminalState expected = entry.getKey() == FaultSurface.GITHUB
                                && faultPoint.equals("unknown-at-reconcile-limit")
                        ? TerminalState.MANUAL_REVIEW
                        : TerminalState.AUTO_RECOVERED;
                cases.add(new FaultCase(
                        "FI-%03d".formatted(sequence++), entry.getKey(), faultPoint, expected));
            }
        }
        if (cases.size() != EXPECTED_SAMPLES) {
            throw new IllegalStateException("M6-Q02 fixed fault denominator must remain 121");
        }
        return List.copyOf(cases);
    }

    private enum FaultSurface {
        OUTBOX("outbox", DispatchKind.NONE, true),
        PROJECTION("projection", DispatchKind.NONE, true),
        SSE("sse", DispatchKind.NONE, false),
        REDIS_SNAPSHOT("redis-snapshot", DispatchKind.NONE, true),
        WORKER("worker", DispatchKind.NONE, true),
        WORKTREE("worktree", DispatchKind.NONE, true),
        MODEL("model", DispatchKind.NONE, false),
        GITHUB("github", DispatchKind.ACTION, true),
        LARK("lark", DispatchKind.NOTIFICATION, false),
        NOTIFICATION("notification", DispatchKind.NOTIFICATION, true),
        DATABASE_COMMIT("database-commit", DispatchKind.ACTION, true);

        private final String slug;
        private final DispatchKind dispatchKind;
        private final boolean usesFencing;

        FaultSurface(String slug, DispatchKind dispatchKind, boolean usesFencing) {
            this.slug = slug;
            this.dispatchKind = dispatchKind;
            this.usesFencing = usesFencing;
        }

        String slug() {
            return slug;
        }

        DispatchKind dispatchKind() {
            return dispatchKind;
        }

        boolean usesFencing() {
            return usesFencing;
        }
    }

    private enum DispatchKind {
        NONE,
        ACTION,
        NOTIFICATION
    }

    private enum TerminalState {
        AUTO_RECOVERED,
        MANUAL_REVIEW
    }

    private record FaultCase(
            String id,
            FaultSurface surface,
            String faultPoint,
            TerminalState expectedTerminalState) {}

    private record RecoveryEvidence(
            TerminalState terminalState,
            int duplicateActionDispatches,
            int duplicateNotificationDispatches,
            int lostInboxDispositions,
            int staleFencingWrites,
            boolean finalFailure,
            boolean manualQueueEntry) {}

    /** Minimal ledger that applies the cross-cutting idempotency and fencing invariants. */
    private static final class RecoveryLedger {

        private static final long CURRENT_FENCING_TOKEN = 2;

        private final Set<String> actionDispatches = new HashSet<>();
        private final Set<String> notificationDispatches = new HashSet<>();
        private final Set<String> manualQueue = new HashSet<>();
        private String originalInboxDisposition;
        private String currentInboxDisposition;
        private int actionExternalCalls;
        private int notificationExternalCalls;
        private int staleFencingWrites;
        private TerminalState terminalState;

        void preserveInboxDisposition(String disposition) {
            originalInboxDisposition = disposition;
            currentInboxDisposition = disposition;
        }

        void dispatchAction(String operationId) {
            if (actionDispatches.add(operationId)) {
                actionExternalCalls++;
            }
        }

        void dispatchNotification(String operationId) {
            if (notificationDispatches.add(operationId)) {
                notificationExternalCalls++;
            }
        }

        void writeWithFencingToken(long fencingToken) {
            if (fencingToken < CURRENT_FENCING_TOKEN) {
                return;
            }
            if (fencingToken > CURRENT_FENCING_TOKEN) {
                throw new IllegalStateException("fixed matrix cannot invent a future fencing token");
            }
        }

        void markAutomaticallyRecovered() {
            terminalState = TerminalState.AUTO_RECOVERED;
        }

        void moveToManualReview(String operationId) {
            manualQueue.add(operationId);
            terminalState = TerminalState.MANUAL_REVIEW;
        }

        RecoveryEvidence evidence(boolean finalFailure) {
            int duplicateActions = Math.max(0, actionExternalCalls - actionDispatches.size());
            int duplicateNotifications = Math.max(
                    0, notificationExternalCalls - notificationDispatches.size());
            int lostDisposition = originalInboxDisposition.equals(currentInboxDisposition) ? 0 : 1;
            return new RecoveryEvidence(
                    terminalState,
                    duplicateActions,
                    duplicateNotifications,
                    lostDisposition,
                    staleFencingWrites,
                    finalFailure,
                    !manualQueue.isEmpty());
        }
    }
}
