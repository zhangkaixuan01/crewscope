package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable path-independent key resolved only inside a configured managed repository root. */
public record RepositoryKey(String value) {

    public static final String FORMAT_REGEX = "[a-z0-9][a-z0-9-]{0,62}";

    private static final Pattern FORMAT = Pattern.compile(FORMAT_REGEX);

    public RepositoryKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException(
                    "repositoryBinding.repositoryKey", "must match " + FORMAT_REGEX);
        }
    }

    public static RepositoryKey parse(String value) {
        return new RepositoryKey(value);
    }
}
