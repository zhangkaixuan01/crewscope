package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Extensible model capability key used by catalog and Agent template policy intersection. */
public record ModelCapability(String value) implements Comparable<ModelCapability> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

    public ModelCapability {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "modelCatalog.capability", "must be a lower-case stable capability key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(ModelCapability other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
