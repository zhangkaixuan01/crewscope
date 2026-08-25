package io.crewscope.domain.projection;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded low-cardinality failure code safe for diagnostics and Audit. */
public record ProjectionFailureCode(String value) {

    public static final int MAX_LENGTH = 80;
    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");

    public ProjectionFailureCode {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ProjectionFailureCode must be an upper-snake diagnostic code");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
