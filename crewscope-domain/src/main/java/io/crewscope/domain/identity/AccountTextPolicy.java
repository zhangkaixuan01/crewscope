package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.text.Normalizer;

/** Shared Unicode and length checks for public account profile values. */
final class AccountTextPolicy {

    private AccountTextPolicy() {}

    static String displayText(String value, String field, int minimum, int maximum) {
        if (value == null) {
            throw new DomainValidationException(field, "must not be null");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < minimum || length > maximum) {
            throw new DomainValidationException(
                    field,
                    "must contain between " + minimum + " and " + maximum + " characters");
        }
        if (normalized.codePoints().anyMatch(AccountTextPolicy::isUnsafe)) {
            throw new DomainValidationException(field, "must contain safe Unicode text");
        }
        return normalized;
    }

    static String compatibilityKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT);
    }

    static boolean isUnsafe(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || type == Character.PRIVATE_USE
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
