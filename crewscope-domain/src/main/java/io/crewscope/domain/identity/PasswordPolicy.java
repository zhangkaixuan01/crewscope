package io.crewscope.domain.identity;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Password input budget and local common-password policy fixed by ADR-025. */
public final class PasswordPolicy {

    public static final int MIN_REGISTRATION_CODE_POINTS = 12;
    public static final int MAX_CODE_POINTS = 128;
    public static final int MAX_UTF8_BYTES = 512;

    private static final PasswordPolicy STANDARD = new PasswordPolicy();
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "123456789012",
            "adminadmin123",
            "administrator",
            "changeme1234",
            "iloveyou12345",
            "letmein12345",
            "password1234",
            "passwordpassword",
            "qwerty123456",
            "welcome12345");

    private PasswordPolicy() {}

    public static PasswordPolicy standard() {
        return STANDARD;
    }

    /** Applies registration/change-password strength rules without storing or rewriting the value. */
    public PasswordPolicyResult evaluateForRegistration(String rawPassword) {
        EncodedBudget budget = inspect(rawPassword);
        if (!budget.validEncoding()) {
            return PasswordPolicyResult.INVALID_ENCODING;
        }
        if (budget.codePoints() < MIN_REGISTRATION_CODE_POINTS) {
            return PasswordPolicyResult.TOO_SHORT;
        }
        PasswordPolicyResult maximumResult = evaluateMaximums(budget);
        if (!maximumResult.isAccepted()) {
            return maximumResult;
        }
        // Case folding is only used for deny-list comparison; the hash input remains untouched.
        if (COMMON_PASSWORDS.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            return PasswordPolicyResult.COMMON_PASSWORD;
        }
        return PasswordPolicyResult.ACCEPTED;
    }

    /** Login only enforces the safe upper budget so legacy short passwords still take one match. */
    public PasswordPolicyResult evaluateForAuthentication(String rawPassword) {
        EncodedBudget budget = inspect(rawPassword);
        if (!budget.validEncoding()) {
            return PasswordPolicyResult.INVALID_ENCODING;
        }
        return evaluateMaximums(budget);
    }

    private static PasswordPolicyResult evaluateMaximums(EncodedBudget budget) {
        if (budget.utf8Bytes() > MAX_UTF8_BYTES) {
            return PasswordPolicyResult.TOO_LARGE;
        }
        if (budget.codePoints() > MAX_CODE_POINTS) {
            return PasswordPolicyResult.TOO_LONG;
        }
        return PasswordPolicyResult.ACCEPTED;
    }

    private static EncodedBudget inspect(String rawPassword) {
        if (rawPassword == null) {
            return EncodedBudget.invalid();
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(rawPassword));
            return new EncodedBudget(
                    rawPassword.codePointCount(0, rawPassword.length()), encoded.remaining(), true);
        } catch (CharacterCodingException invalidUnicode) {
            return EncodedBudget.invalid();
        }
    }

    private record EncodedBudget(int codePoints, int utf8Bytes, boolean validEncoding) {

        private static EncodedBudget invalid() {
            return new EncodedBudget(0, 0, false);
        }
    }
}
