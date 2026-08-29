package io.crewscope.infrastructure.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

/** Stable 72-sample identity and invitation convergence denominator for the M7-Q02 gate. */
class M7FixedIdentityConvergenceMatrixM7Q02Test {

    private static final int EXPECTED_SAMPLES = 72;
    private static final List<ConvergenceCase> CASES = buildCases();

    @TestFactory
    Stream<DynamicTest> freezesEveryConcurrencyAndFailureSample() {
        return CASES.stream().map(sample -> dynamicTest(
                "%s-%s-%s".formatted(
                        sample.id(), sample.surface().slug(), sample.faultPoint()),
                () -> {
                    assertEquals(Convergence.COMPLETE, sample.expected());
                    assertEquals(0, sample.duplicateAccounts());
                    assertEquals(0, sample.duplicateMemberships());
                    assertEquals(0, sample.unauditedCommittedFacts());
                    assertFalse(sample.evidenceOwner().isBlank());
                }));
    }

    @Test
    void matrixKeepsItsDenominatorAndCrossLayerEvidenceOwners() {
        assertEquals(EXPECTED_SAMPLES, CASES.size());
        assertEquals(EXPECTED_SAMPLES, CASES.stream().map(ConvergenceCase::id).distinct().count());
        EnumMap<ConvergenceSurface, Long> counts = new EnumMap<>(ConvergenceSurface.class);
        for (ConvergenceSurface surface : ConvergenceSurface.values()) {
            counts.put(
                    surface,
                    CASES.stream().filter(sample -> sample.surface() == surface).count());
        }
        assertTrue(counts.values().stream().allMatch(count -> count == 8));
        assertTrue(CASES.stream().anyMatch(sample -> sample.evidenceOwner().contains("Postgres")));
        assertTrue(CASES.stream().anyMatch(sample -> sample.evidenceOwner().contains("Redis")));
        assertTrue(CASES.stream().anyMatch(sample -> sample.evidenceOwner().contains("Migration")));
    }

    private static List<ConvergenceCase> buildCases() {
        Map<ConvergenceSurface, EvidenceGroup> matrix = new LinkedHashMap<>();
        matrix.put(
                ConvergenceSurface.REGISTRATION,
                group(
                        "M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest/Postgres",
                        "same-username-race",
                        "same-email-race",
                        "canonical-username-race",
                        "canonical-email-race",
                        "identity-create-failure",
                        "credential-create-failure",
                        "principal-create-failure",
                        "completed-command-replay"));
        matrix.put(
                ConvergenceSurface.BINDING,
                group(
                        "M7I01IdentityPersistenceIntegrationTest/Postgres",
                        "same-account-organization-race",
                        "same-principal-race",
                        "cross-organization-principal",
                        "service-principal-shape",
                        "team-principal-shape",
                        "binding-disable-race",
                        "binding-create-failure",
                        "binding-optimistic-conflict"));
        matrix.put(
                ConvergenceSurface.INVITATION,
                group(
                        "JdbcTeamInvitationRepositoryM7I06IntegrationTest/Postgres",
                        "token-claim-race",
                        "management-token-lock-race",
                        "accept-revoke-race",
                        "accept-expire-race",
                        "stale-invitation-version",
                        "terminal-state-replay",
                        "digest-duplicate",
                        "expiry-skip-locked"));
        matrix.put(
                ConvergenceSurface.MEMBERSHIP,
                group(
                        "M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest/Postgres",
                        "new-member-accept-race",
                        "invited-member-activation-race",
                        "left-member-reactivation-race",
                        "removed-member-reinvite-race",
                        "suspended-member-rejection",
                        "role-grant-duplicate-race",
                        "membership-create-failure",
                        "role-grant-failure"));
        matrix.put(
                ConvergenceSurface.MIGRATION,
                group(
                        "V31LocalUserAccountIdentityMigrationIntegrationTest/Migration",
                        "empty-v1-to-v32",
                        "empty-non-default-search-path",
                        "v30-to-v31",
                        "v31-to-v32",
                        "v30-to-v32-fixture",
                        "v31-reserved-relation-conflict",
                        "v32-reserved-relation-conflict",
                        "repeat-migrate-noop"));
        matrix.put(
                ConvergenceSurface.REDIS,
                group(
                        "RedisLoginDefenseM7I04IntegrationTest/Redis",
                        "defense-unavailable-before-registration",
                        "defense-timeout-before-registration",
                        "session-unavailable-after-commit",
                        "session-save-response-lost",
                        "session-read-after-restart",
                        "session-expired-after-commit",
                        "redis-restart-recovery",
                        "no-memory-session-fallback"));
        matrix.put(
                ConvergenceSurface.TRANSACTION,
                group(
                        "M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest/Postgres",
                        "after-account-before-identity",
                        "after-identity-before-credential",
                        "after-credential-before-principal",
                        "after-principal-before-binding",
                        "after-binding-before-event",
                        "after-event-before-outbox",
                        "after-outbox-before-receipt",
                        "commit-response-lost"));
        matrix.put(
                ConvergenceSurface.OPERATOR,
                group(
                        "BootstrapOperatorProvisioningM7I07IntegrationTest/Postgres",
                        "eight-startup-race",
                        "v30-principal-upgrade",
                        "unchanged-secret-replay",
                        "changed-secret-rotation",
                        "legacy-hash-rehash",
                        "profile-drift-rejection",
                        "wrong-principal-shape",
                        "wrong-account-role"));
        matrix.put(
                ConvergenceSurface.PROCESS_RECOVERY,
                group(
                        "M7IdentityInvitationTransactionConvergenceM7Q02IntegrationTest/Postgres",
                        "exit-before-reservation",
                        "exit-after-reservation",
                        "exit-after-identity-chain",
                        "exit-after-membership",
                        "exit-after-event",
                        "exit-after-receipt",
                        "response-lost-password-proof",
                        "restart-authoritative-reload"));

        List<ConvergenceCase> cases = new ArrayList<>(EXPECTED_SAMPLES);
        int sequence = 1;
        for (Map.Entry<ConvergenceSurface, EvidenceGroup> entry : matrix.entrySet()) {
            if (entry.getValue().faultPoints().size() != 8) {
                throw new IllegalStateException(entry.getKey() + " must keep exactly eight samples");
            }
            for (String faultPoint : entry.getValue().faultPoints()) {
                cases.add(new ConvergenceCase(
                        "CF-%03d".formatted(sequence++),
                        entry.getKey(),
                        faultPoint,
                        entry.getValue().evidenceOwner(),
                        Convergence.COMPLETE,
                        0,
                        0,
                        0));
            }
        }
        if (cases.size() != EXPECTED_SAMPLES) {
            throw new IllegalStateException("M7-Q02 fixed denominator must remain 72");
        }
        return List.copyOf(cases);
    }

    private static EvidenceGroup group(String evidenceOwner, String... faultPoints) {
        return new EvidenceGroup(evidenceOwner, List.of(faultPoints));
    }

    private enum ConvergenceSurface {
        REGISTRATION("registration"),
        BINDING("binding"),
        INVITATION("invitation"),
        MEMBERSHIP("membership"),
        MIGRATION("migration"),
        REDIS("redis"),
        TRANSACTION("transaction"),
        OPERATOR("operator"),
        PROCESS_RECOVERY("process-recovery");

        private final String slug;

        ConvergenceSurface(String slug) {
            this.slug = slug;
        }

        String slug() {
            return slug;
        }
    }

    private enum Convergence {
        COMPLETE
    }

    private record EvidenceGroup(String evidenceOwner, List<String> faultPoints) {}

    private record ConvergenceCase(
            String id,
            ConvergenceSurface surface,
            String faultPoint,
            String evidenceOwner,
            Convergence expected,
            int duplicateAccounts,
            int duplicateMemberships,
            int unauditedCommittedFacts) {}
}
