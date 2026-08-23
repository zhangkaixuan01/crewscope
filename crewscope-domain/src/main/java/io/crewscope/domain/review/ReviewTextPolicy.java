package io.crewscope.domain.review;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Shared bounded text and fingerprint normalization for Review facts. */
final class ReviewTextPolicy {

    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");

    private ReviewTextPolicy() {}

    static String requireText(String value, String field, int maximumLength) {
        String normalized = Normalizer.normalize(
                        Objects.requireNonNull(value, field), Normalizer.Form.NFC)
                .strip();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0) {
            throw new DomainValidationException(field, "must contain bounded text");
        }
        if (normalized.length() > maximumLength) {
            throw new DomainValidationException(
                    field, "must contain at most " + maximumLength + " characters");
        }
        return normalized;
    }

    static String normalizeClaim(String value) {
        String normalized = Normalizer.normalize(
                        Objects.requireNonNull(value, "claim"), Normalizer.Form.NFKC)
                .strip();
        return WHITESPACE.matcher(normalized).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
