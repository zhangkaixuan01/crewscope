package io.crewscope.domain.identity;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** User-editable username with a separate compatibility-normalized uniqueness key. */
public final class Username {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 64;

    private final String displayValue;
    private final String normalizedValue;

    public Username(String value) {
        this.displayValue = AccountTextPolicy.displayText(
                value, "userAccount.username", MIN_LENGTH, MAX_LENGTH);
        this.normalizedValue = AccountTextPolicy.compatibilityKey(displayValue);
        int normalizedLength = normalizedValue.codePointCount(0, normalizedValue.length());
        if (normalizedLength < MIN_LENGTH || normalizedLength > MAX_LENGTH) {
            throw new DomainValidationException(
                    "userAccount.username",
                    "normalized value must contain between "
                            + MIN_LENGTH
                            + " and "
                            + MAX_LENGTH
                            + " characters");
        }
        validateCharacters(normalizedValue);
    }

    public String displayValue() {
        return displayValue;
    }

    public String normalizedValue() {
        return normalizedValue;
    }

    private static void validateCharacters(String value) {
        int[] points = value.codePoints().toArray();
        if (!Character.isLetterOrDigit(points[0])
                || !isTerminal(points[points.length - 1])) {
            throw new DomainValidationException(
                    "userAccount.username", "must start and end with a letter or number");
        }
        boolean separator = false;
        for (int point : points) {
            if (Character.isLetterOrDigit(point)) {
                separator = false;
                continue;
            }
            if (isCombiningMark(point) && !separator) {
                continue;
            }
            if ((point == '.' || point == '-' || point == '_') && !separator) {
                separator = true;
                continue;
            }
            throw new DomainValidationException(
                    "userAccount.username",
                    "may contain letters, numbers and single '.', '-' or '_' separators");
        }
    }

    private static boolean isTerminal(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || isCombiningMark(codePoint);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Username username
                        && displayValue.equals(username.displayValue)
                        && normalizedValue.equals(username.normalizedValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(displayValue, normalizedValue);
    }

    @Override
    public String toString() {
        return displayValue;
    }
}
