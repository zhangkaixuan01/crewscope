package io.crewscope.application.command;

import java.util.regex.Pattern;

/** Organization-scoped, client-generated key for one logical command. */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 200;
    public static final String FORMAT_REGEX = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}";
    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new IllegalArgumentException("idempotencyKey has an invalid format");
        }
        value = value.strip();
    }

    public static IdempotencyKey from(String value) {
        return new IdempotencyKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
