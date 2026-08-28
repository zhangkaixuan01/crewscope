package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.net.IDN;
import java.util.Locale;

/** Case- and IDN-normalized email uniqueness key derived from a separately retained display value. */
public record NormalizedEmail(String value) {

    public static final int MAX_LENGTH = 254;
    private static final int MAX_LOCAL_PART_LENGTH = 64;

    public NormalizedEmail {
        if (value == null || value.isBlank()) {
            throw invalid("must not be blank");
        }
        String canonical = AccountTextPolicy.compatibilityKey(value.strip());
        int separator = canonical.indexOf('@');
        if (separator <= 0
                || separator != canonical.lastIndexOf('@')
                || separator == canonical.length() - 1) {
            throw invalid("must contain one local part and one domain");
        }
        String localPart = canonical.substring(0, separator);
        String domain = canonical.substring(separator + 1);
        validateLocalPart(localPart);
        String asciiDomain = normalizeDomain(domain);
        value = localPart + "@" + asciiDomain;
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            throw invalid("must contain at most " + MAX_LENGTH + " characters");
        }
    }

    public static NormalizedEmail fromDisplayValue(String displayValue) {
        String safeDisplay = AccountTextPolicy.displayText(
                displayValue, "userAccount.email", 3, MAX_LENGTH);
        return new NormalizedEmail(safeDisplay);
    }

    private static void validateLocalPart(String value) {
        int length = value.codePointCount(0, value.length());
        if (length == 0 || length > MAX_LOCAL_PART_LENGTH) {
            throw invalid("local part must contain at most " + MAX_LOCAL_PART_LENGTH + " characters");
        }
        if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            throw invalid("local part has an invalid dot position");
        }
        if (value.codePoints().anyMatch(NormalizedEmail::isForbiddenLocalCharacter)) {
            throw invalid("local part contains an unsupported character");
        }
    }

    private static String normalizeDomain(String value) {
        if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            throw invalid("domain has an invalid dot position");
        }
        try {
            String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.isBlank() || ascii.length() > 253) {
                throw invalid("domain has an invalid length");
            }
            return ascii;
        } catch (IllegalArgumentException invalidDomain) {
            throw invalid("domain is invalid");
        }
    }

    private static boolean isForbiddenLocalCharacter(int codePoint) {
        if (AccountTextPolicy.isUnsafe(codePoint) || Character.isWhitespace(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        boolean mark = type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
        return !Character.isLetterOrDigit(codePoint)
                && !mark
                && ".!#$%&'*+/=?^_`{|}~-".indexOf(codePoint) < 0;
    }

    private static DomainValidationException invalid(String reason) {
        return new DomainValidationException("userAccount.email", reason);
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
