package io.crewscope.domain.team;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Organization-stable machine key for a role definition. */
public record TeamRoleKey(String value) {

    public static final int MAX_LENGTH = 64;
    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    public TeamRoleKey {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException("teamRole.key", "must not be blank");
        }
        value = value.strip().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new DomainValidationException(
                    "teamRole.key",
                    "must start with a letter and contain 2-64 uppercase letters, digits or underscores");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
