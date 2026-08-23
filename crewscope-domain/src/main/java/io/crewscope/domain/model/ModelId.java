package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable provider-facing model identifier, never inferred from a display name. */
public record ModelId(String value) implements Comparable<ModelId> {

    private static final Pattern FORMAT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,199}");

    public ModelId {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "modelCatalog.modelId", "must be a stable provider model identifier");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(ModelId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
