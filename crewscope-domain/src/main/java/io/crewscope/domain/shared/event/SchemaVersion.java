package io.crewscope.domain.shared.event;

/** Positive event payload schema version serialized as decimal text. */
public record SchemaVersion(int value) implements Comparable<SchemaVersion> {

    public static final SchemaVersion V1 = new SchemaVersion(1);

    public SchemaVersion {
        if (value < 1) {
            throw new IllegalArgumentException("SchemaVersion must be positive");
        }
    }

    /** Parses the canonical decimal representation used by JSON and PostgreSQL. */
    public static SchemaVersion from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SchemaVersion must not be blank");
        }
        String candidate = value.strip();
        if (!candidate.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("SchemaVersion must be a positive decimal integer");
        }
        try {
            return new SchemaVersion(Integer.parseInt(candidate));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("SchemaVersion exceeds the supported range", exception);
        }
    }

    @Override
    public int compareTo(SchemaVersion other) {
        return Integer.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
