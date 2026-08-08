package io.crewscope.domain.provider;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable lowercase capability key exposed by a Provider contract. */
public record ProviderCapability(String value) implements Comparable<ProviderCapability> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

    public ProviderCapability {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "providerCapability", "must be a lowercase stable capability key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(ProviderCapability other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
