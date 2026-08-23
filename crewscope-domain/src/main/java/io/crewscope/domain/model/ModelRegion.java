package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable model serving and data residency region key. */
public record ModelRegion(String value) implements Comparable<ModelRegion> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public ModelRegion {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "modelRegion", "must be a lower-case stable region key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(ModelRegion other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
