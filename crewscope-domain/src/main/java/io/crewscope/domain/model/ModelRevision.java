package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Exact provider model revision fixed by one CrewScope catalog revision. */
public record ModelRevision(String value) implements Comparable<ModelRevision> {

    private static final Pattern FORMAT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,127}");

    public ModelRevision {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "modelCatalog.modelRevision", "must be a stable exact model revision");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(ModelRevision other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
