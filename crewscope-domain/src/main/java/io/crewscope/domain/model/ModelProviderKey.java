package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable product-facing model provider key, independent from its runtime adapter. */
public record ModelProviderKey(String value) implements Comparable<ModelProviderKey> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public ModelProviderKey {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "modelProvider.key", "must be a lower-case stable provider key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(ModelProviderKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
