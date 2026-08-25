package io.crewscope.domain.projection;

import java.util.regex.Pattern;

/** Stable lower-kebab identifier shared by projection definitions, rows and cursors. */
public record ProjectionName(String value) {

    public static final int MAX_LENGTH = 180;
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    public ProjectionName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ProjectionName must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ProjectionName must be lower kebab case and contain at most "
                            + MAX_LENGTH
                            + " characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
