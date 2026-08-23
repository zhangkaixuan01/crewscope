package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.regex.Pattern;

/** Stable key of a trusted AgentScope model adapter implementation. */
public record ModelAdapterKey(String value) implements Comparable<ModelAdapterKey> {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public ModelAdapterKey {
        if (value == null || !FORMAT.matcher(value.strip()).matches()) {
            throw new DomainValidationException(
                    "modelProvider.adapterKey", "must be a lower-case stable adapter key");
        }
        value = value.strip();
    }

    @Override
    public int compareTo(ModelAdapterKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
