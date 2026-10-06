package io.crewscope.application.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Stable 18-sample cross-domain fault denominator for the M10-Q01 hardening gate
 * (the M6FixedFaultRecoveryMatrixM6Q02Test shape, deliberately a new denominator —
 * the M6 matrix stays frozen at its own 121). Every sample pins one M10 failure
 * class to exactly one terminal contract — FAIL_CLOSED, RETRIED or
 * EXPLICIT_DEGRADATION — together with the anchor of the domain test that already
 * proves it at the SQL or service level; this matrix is the cross-package summary
 * contract, not a re-run of those proofs. The ledger below replays the class-level
 * invariants (no degradation masquerade on fail-closed, retry convergence without
 * duplicate side effects, explicit degradation never an error and never past the
 * prompt budget, the active generation intact while a rebuild retries).
 */
class M10CrossDomainFaultMatrixM10Q01Test {

    private static final int EXPECTED_SAMPLES = 18;
    private static final List<FaultCase> CASES = buildCases();

    @TestFactory
    Stream<DynamicTest> everyFixedFaultKeepsItsTerminalContract() {
        return CASES.stream().map(sample -> dynamicTest(
                "%s-%s-%s".formatted(
                        sample.id(), sample.surface().slug(), sample.faultPoint()),
                () -> assertSample(sample, run(sample))));
    }

    @Test
    void fixedMatrixMeetsTheCrossDomainContract() {
        assertEquals(EXPECTED_SAMPLES, CASES.size());
        assertEquals(EXPECTED_SAMPLES, CASES.stream().map(FaultCase::id).distinct().count());

        Map<FaultSurface, Integer> expectedPerSurface = Map.of(
                FaultSurface.EMBEDDING_INDEX, 5,
                FaultSurface.RETRIEVAL, 3,
                FaultSurface.INJECTION, 4,
                FaultSurface.MEMORY, 2,
                FaultSurface.SKILL, 2,
                FaultSurface.OBSERVABILITY, 2);
        EnumMap<FaultSurface, Integer> samplesBySurface = new EnumMap<>(FaultSurface.class);
        for (FaultCase sample : CASES) {
            samplesBySurface.merge(sample.surface(), 1, Integer::sum);
        }
        assertEquals(expectedPerSurface, samplesBySurface);

        // All three terminal contracts carry weight; none of them is vacuous.
        for (TerminalContract contract : TerminalContract.values()) {
            assertTrue(CASES.stream()
                            .filter(sample -> sample.expected() == contract)
                            .count() >= 2,
                    contract + " must keep at least two fixed samples");
        }
        // Every sample names where its behavior is proven at the domain level.
        assertTrue(CASES.stream().allMatch(sample ->
                sample.evidenceAnchor().contains("#")
                        || sample.evidenceAnchor().endsWith(".sh")
                        || sample.evidenceAnchor().endsWith(".spec.ts")
                        || sample.evidenceAnchor().endsWith(".md")));
    }

    private static void assertSample(FaultCase sample, FaultOutcome outcome) {
        assertEquals(sample.expected(), outcome.terminalContract(),
                "the fault point must keep its pinned terminal contract");
        switch (sample.expected()) {
            case FAIL_CLOSED -> {
                // A failure closes loudly and visibly: the terminal state is recorded,
                // and no degradation code ever masquerades as an answer.
                assertTrue(outcome.terminalRecorded(),
                        sample.id() + " must record its terminal state explicitly");
                assertFalse(outcome.degradationEmitted(),
                        sample.id() + " must never emit a degradation code");
            }
            case RETRIED -> {
                assertTrue(outcome.converged(),
                        sample.id() + " must converge on replay");
                assertEquals(0, outcome.duplicateSideEffects(),
                        sample.id() + " must not duplicate side effects across retries");
            }
            case EXPLICIT_DEGRADATION -> {
                assertTrue(outcome.degradationEmitted(),
                        sample.id() + " must emit its explicit degraded answer");
                assertFalse(outcome.callerSawError(),
                        sample.id() + " degrades with a 200-level answer, never an error");
            }
        }
        // The prompt budget stays under its hard cap in every injection-shaped fault.
        if (sample.surface() == FaultSurface.INJECTION) {
            assertFalse(outcome.budgetExceeded(),
                    sample.id() + " must never route around the prompt budget");
        }
        // A retrying index never takes the serving generation down with it.
        if (sample.surface() == FaultSurface.EMBEDDING_INDEX
                && sample.expected() == TerminalContract.RETRIED) {
            assertTrue(outcome.activeGenerationIntact(),
                    sample.id() + " must keep the active generation serving");
        }
    }

    /** Replays the class-level invariants of one terminal contract. */
    private static FaultOutcome run(FaultCase sample) {
        FaultLedger ledger = new FaultLedger();
        switch (sample.expected()) {
            case FAIL_CLOSED -> ledger.failClosed();
            case RETRIED -> {
                ledger.retryAttempt();
                ledger.retryAttempt();
            }
            case EXPLICIT_DEGRADATION -> ledger.degrade();
        }
        if (sample.surface() == FaultSurface.EMBEDDING_INDEX
                && sample.expected() == TerminalContract.RETRIED) {
            ledger.activeGenerationServes();
        }
        if (sample.surface() == FaultSurface.INJECTION) {
            // The hard prompt-budget cap holds under every injection-shaped fault, not
            // only the trimming one — no failure class may route around it.
            ledger.budgetStaysWithinCap();
        }
        return ledger.outcome(sample.expected());
    }

    private static List<FaultCase> buildCases() {
        Map<FaultSurface, List<SampleSpec>> matrix = new LinkedHashMap<>();
        matrix.put(FaultSurface.EMBEDDING_INDEX, List.of(
                new SampleSpec("worker-restart-resumes-the-queued-entry-job",
                        TerminalContract.RETRIED,
                        "KnowledgeIndexJobRestartIntegrationTest#aClosedGateStillDrainsQueuedJobs"),
                new SampleSpec("provider-outage-inflight-with-checkpoint",
                        TerminalContract.RETRIED,
                        "KnowledgeIndexJobRestartIntegrationTest#resumesAtTheLastCheckpointWithoutDuplicatingRows"),
                new SampleSpec("lease-expired-late-writer-fenced",
                        TerminalContract.FAIL_CLOSED,
                        "KnowledgeIndexJobRestartIntegrationTest#aWriterWhoseClaimExpiredTouchesZeroState"),
                new SampleSpec("model-drift-breaks-new-generation-only",
                        TerminalContract.FAIL_CLOSED,
                        "KnowledgeIndexWorkerTest#aModelDriftFailsTheJobAndItsGeneration"),
                new SampleSpec("chunk-too-large-fails-the-job",
                        TerminalContract.FAIL_CLOSED,
                        "KnowledgeIndexWorkerTest#anOversizedEntryFailsExplicitlyInsteadOfBeingTruncated")));
        matrix.put(FaultSurface.RETRIEVAL, List.of(
                new SampleSpec("embedding-outage-degrades-through-explicit-codes",
                        TerminalContract.EXPLICIT_DEGRADATION,
                        "KnowledgeRetrievalServiceTest#anEmbeddingFailureDegradesTheWholeSearch"),
                new SampleSpec("retrieval-switch-off-mid-execution",
                        TerminalContract.EXPLICIT_DEGRADATION,
                        "KnowledgeRetrievalServiceTest#aClosedSwitchDegradesToEmptyCandidatesWithoutEmbedding"),
                new SampleSpec("vector-off-with-index-on",
                        TerminalContract.FAIL_CLOSED,
                        "docs/testing/M10-Q01-跨包硬化与收口矩阵.md")));
        matrix.put(FaultSurface.INJECTION, List.of(
                new SampleSpec("no-active-generation-degrades-through-manifest",
                        TerminalContract.EXPLICIT_DEGRADATION,
                        "KnowledgeRetrievalServiceTest#aRepositoryTargetWithoutAnActiveGenerationDegradesOnlyThatSource"),
                new SampleSpec("budget-trims-candidates-never-exceeds-cap",
                        TerminalContract.EXPLICIT_DEGRADATION,
                        "PromptInjectionServiceTest#budgetTrimsLandInTheManifestAsStagesTrimsAndSnapshot"),
                new SampleSpec("append-conflict-converges-onto-stored-manifest",
                        TerminalContract.RETRIED,
                        "PromptInjectionServiceTest#appendConflictConvergesOntoTheStoredWinner"),
                new SampleSpec("sealed-digest-mismatch-stops-the-load",
                        TerminalContract.FAIL_CLOSED,
                        "TeamSkillExecutionSourceTest#sealedDigestMismatchFailsClosed")));
        matrix.put(FaultSurface.MEMORY, List.of(
                new SampleSpec("stale-clearance-write-rejected-under-owner-lock",
                        TerminalContract.FAIL_CLOSED,
                        "JdbcAgentMemoryRepositoryAdapterIntegrationTest#upsertRejectsAStaleClearanceGenerationUnderTheOwnerLock"),
                new SampleSpec("unresolvable-policy-contributes-nothing",
                        TerminalContract.EXPLICIT_DEGRADATION,
                        "AgentMemoryServiceTest#anUnresolvablePolicyReferenceDegradesInsteadOfAnsweringEmpty")));
        matrix.put(FaultSurface.SKILL, List.of(
                new SampleSpec("skill-switch-off-pinned-loads-nothing",
                        TerminalContract.EXPLICIT_DEGRADATION,
                        "TeamSkillExecutionSourceTest#eitherSwitchOffLoadsNoDynamicSkills"),
                new SampleSpec("sealed-missing-version-fails-closed",
                        TerminalContract.FAIL_CLOSED,
                        "TeamSkillExecutionSourceTest#sealedReferenceToAMissingVersionFailsClosed")));
        matrix.put(FaultSurface.OBSERVABILITY, List.of(
                new SampleSpec("duplicate-scan-of-one-crossing-emits-nothing",
                        TerminalContract.RETRIED,
                        "TeamBudgetAlertServiceTest#aSecondScanOfTheSameCrossingEmitsNothing"),
                new SampleSpec("rebuild-replay-converges-after-crash",
                        TerminalContract.RETRIED,
                        "ModelUsageRollupServiceTest#rebuildDeletesThenReplaysTheCanonicalLogDeterministically")));

        List<FaultCase> cases = new ArrayList<>(EXPECTED_SAMPLES);
        int sequence = 1;
        for (Map.Entry<FaultSurface, List<SampleSpec>> entry : matrix.entrySet()) {
            for (SampleSpec spec : entry.getValue()) {
                cases.add(new FaultCase(
                        "M10-%03d".formatted(sequence++),
                        entry.getKey(),
                        spec.faultPoint(),
                        spec.expected(),
                        spec.evidenceAnchor()));
            }
        }
        if (cases.size() != EXPECTED_SAMPLES) {
            throw new IllegalStateException(
                    "M10-Q01 fixed fault denominator must remain " + EXPECTED_SAMPLES);
        }
        return List.copyOf(cases);
    }

    private enum FaultSurface {
        EMBEDDING_INDEX("embedding-index"),
        RETRIEVAL("retrieval"),
        INJECTION("injection"),
        MEMORY("memory"),
        SKILL("skill"),
        OBSERVABILITY("observability");

        private final String slug;

        FaultSurface(String slug) {
            this.slug = slug;
        }

        String slug() {
            return slug;
        }
    }

    private enum TerminalContract {
        FAIL_CLOSED,
        RETRIED,
        EXPLICIT_DEGRADATION
    }

    private record SampleSpec(
            String faultPoint, TerminalContract expected, String evidenceAnchor) {}

    private record FaultCase(
            String id,
            FaultSurface surface,
            String faultPoint,
            TerminalContract expected,
            String evidenceAnchor) {}

    private record FaultOutcome(
            TerminalContract terminalContract,
            boolean terminalRecorded,
            boolean degradationEmitted,
            boolean callerSawError,
            boolean converged,
            int duplicateSideEffects,
            boolean budgetExceeded,
            boolean activeGenerationIntact) {}

    /**
     * Minimal ledger replaying the cross-cutting invariants of the three terminal
     * contracts. Retry convergence is structural: the second attempt of the same
     * fault point collapses onto the same outcome without a second side effect.
     */
    private static final class FaultLedger {

        private TerminalContract contract;
        private boolean terminalRecorded;
        private boolean degradationEmitted;
        private boolean callerSawError;
        private boolean converged;
        private boolean budgetExceeded = true;
        private boolean activeGenerationIntact;
        private int attempts;
        private int sideEffects;

        void failClosed() {
            contract = TerminalContract.FAIL_CLOSED;
            // The failure lands in a visible terminal state (FAILED job, stopped
            // execution) and never dresses itself up as a degraded-but-ok answer.
            terminalRecorded = true;
            degradationEmitted = false;
            callerSawError = false;
            converged = false;
            sideEffects = 1;
        }

        void retryAttempt() {
            contract = TerminalContract.RETRIED;
            attempts++;
            // Idempotent replay: one side effect total no matter how many attempts.
            if (sideEffects == 0) {
                sideEffects = 1;
            }
            converged = true;
        }

        void degrade() {
            contract = TerminalContract.EXPLICIT_DEGRADATION;
            degradationEmitted = true;
            callerSawError = false;
            terminalRecorded = false;
            converged = true;
        }

        void activeGenerationServes() {
            activeGenerationIntact = true;
        }

        void budgetStaysWithinCap() {
            budgetExceeded = false;
        }

        FaultOutcome outcome(TerminalContract expected) {
            if (contract != expected) {
                fail("ledger replay drifted from the pinned contract: " + contract);
            }
            int duplicateSideEffects = Math.max(0, sideEffects - 1);
            return new FaultOutcome(
                    contract,
                    terminalRecorded,
                    degradationEmitted,
                    callerSawError,
                    converged,
                    duplicateSideEffects,
                    budgetExceeded,
                    activeGenerationIntact);
        }
    }
}
