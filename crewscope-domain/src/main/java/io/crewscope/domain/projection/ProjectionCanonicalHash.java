package io.crewscope.domain.projection;

import java.util.Objects;

/** Lower-case SHA-256 of sorted canonical projection rows. */
public record ProjectionCanonicalHash(String value) {

    public ProjectionCanonicalHash {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "ProjectionCanonicalHash must be a lowercase SHA-256 digest");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
