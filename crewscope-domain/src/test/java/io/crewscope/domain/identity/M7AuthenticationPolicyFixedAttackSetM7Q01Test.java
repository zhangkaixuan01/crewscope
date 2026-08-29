package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Stable M7-Q01 password-budget and brute-force policy attack denominator. */
class M7AuthenticationPolicyFixedAttackSetM7Q01Test {

    private static final int PASSWORD_ATTACKS = 16;
    private static final int BRUTE_FORCE_ATTACKS = 12;
    private static final PasswordPolicy PASSWORDS = PasswordPolicy.standard();
    private static final LoginAttemptPolicy ATTEMPTS = LoginAttemptPolicy.standard();

    @TestFactory
    Stream<DynamicTest> blocksPasswordPolicyAttacks() {
        List<PasswordAttack> attacks = List.of(
                registration("PW-01", null, PasswordPolicyResult.INVALID_ENCODING),
                registration("PW-02", "", PasswordPolicyResult.TOO_SHORT),
                registration("PW-03", "a".repeat(11), PasswordPolicyResult.TOO_SHORT),
                registration("PW-04", "password1234", PasswordPolicyResult.COMMON_PASSWORD),
                registration("PW-05", "PASSWORD1234", PasswordPolicyResult.COMMON_PASSWORD),
                registration("PW-06", "123456789012", PasswordPolicyResult.COMMON_PASSWORD),
                registration("PW-07", "qwerty123456", PasswordPolicyResult.COMMON_PASSWORD),
                registration("PW-08", "a".repeat(129), PasswordPolicyResult.TOO_LONG),
                registration("PW-09", "😀".repeat(129), PasswordPolicyResult.TOO_LARGE),
                registration("PW-10", "broken-\uD800", PasswordPolicyResult.INVALID_ENCODING),
                authentication("PW-11", null, PasswordPolicyResult.INVALID_ENCODING),
                authentication("PW-12", "a".repeat(129), PasswordPolicyResult.TOO_LONG),
                authentication("PW-13", "😀".repeat(129), PasswordPolicyResult.TOO_LARGE),
                authentication("PW-14", "broken-\uD800", PasswordPolicyResult.INVALID_ENCODING),
                authentication("PW-15", "a".repeat(513), PasswordPolicyResult.TOO_LARGE),
                authentication("PW-16", "界".repeat(171), PasswordPolicyResult.TOO_LARGE));
        assertStableIds(attacks.stream().map(PasswordAttack::id).toList(), "PW", PASSWORD_ATTACKS);
        return attacks.stream().map(attack -> DynamicTest.dynamicTest(
                attack.id(),
                () -> assertEquals(
                        attack.expected(), attack.evaluator().apply(attack.value()))));
    }

    @TestFactory
    Stream<DynamicTest> blocksBruteForceBudgetAttacks() {
        List<BruteForceAttack> attacks = List.of(
                allowed("BF-01", ATTEMPTS::allowsIdentifierAttempt, 10, false),
                allowed("BF-02", ATTEMPTS::allowsIdentifierAttempt, 11, false),
                allowed("BF-03", ATTEMPTS::allowsIdentifierAttempt, Integer.MAX_VALUE, false),
                rejectedCounter("BF-04", ATTEMPTS::allowsIdentifierAttempt, -1),
                allowed("BF-05", ATTEMPTS::allowsControlledNetworkAttempt, 60, false),
                allowed("BF-06", ATTEMPTS::allowsControlledNetworkAttempt, 61, false),
                allowed("BF-07", ATTEMPTS::allowsControlledNetworkAttempt, Integer.MAX_VALUE, false),
                rejectedCounter("BF-08", ATTEMPTS::allowsControlledNetworkAttempt, -1),
                allowed("BF-09", ATTEMPTS::shouldTemporarilyLock, 10, true),
                allowed("BF-10", ATTEMPTS::shouldTemporarilyLock, 11, true),
                allowed("BF-11", ATTEMPTS::shouldTemporarilyLock, Integer.MAX_VALUE, true),
                rejectedCounter("BF-12", ATTEMPTS::shouldTemporarilyLock, -1));
        assertStableIds(attacks.stream().map(BruteForceAttack::id).toList(), "BF", BRUTE_FORCE_ATTACKS);
        return attacks.stream().map(attack -> DynamicTest.dynamicTest(attack.id(), attack.assertion()));
    }

    private static PasswordAttack registration(
            String id, String value, PasswordPolicyResult expected) {
        return new PasswordAttack(id, value, PASSWORDS::evaluateForRegistration, expected);
    }

    private static PasswordAttack authentication(
            String id, String value, PasswordPolicyResult expected) {
        return new PasswordAttack(id, value, PASSWORDS::evaluateForAuthentication, expected);
    }

    private static BruteForceAttack allowed(
            String id, java.util.function.IntPredicate operation, int count, boolean expected) {
        return new BruteForceAttack(id, () -> assertEquals(expected, operation.test(count)));
    }

    private static BruteForceAttack rejectedCounter(
            String id, java.util.function.IntPredicate operation, int count) {
        return new BruteForceAttack(id, () -> assertThrows(
                io.crewscope.domain.shared.error.DomainValidationException.class,
                () -> operation.test(count)));
    }

    private static void assertStableIds(List<String> ids, String prefix, int expected) {
        assertEquals(expected, ids.size());
        assertEquals(expected, new java.util.HashSet<>(ids).size());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, expected)
                .mapToObj(index -> "%s-%02d".formatted(prefix, index))
                .toList(), ids);
    }

    private record PasswordAttack(
            String id,
            String value,
            java.util.function.Function<String, PasswordPolicyResult> evaluator,
            PasswordPolicyResult expected) {}

    private record BruteForceAttack(String id, org.junit.jupiter.api.function.Executable assertion) {}
}
