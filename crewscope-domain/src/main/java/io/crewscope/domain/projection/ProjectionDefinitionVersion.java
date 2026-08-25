package io.crewscope.domain.projection;

/** Positive version of a projection's mapping, canonical encoding and validation contract. */
public record ProjectionDefinitionVersion(long value)
        implements Comparable<ProjectionDefinitionVersion> {

    public static final ProjectionDefinitionVersion V1 = new ProjectionDefinitionVersion(1);

    public ProjectionDefinitionVersion {
        if (value < 1) {
            throw new IllegalArgumentException("ProjectionDefinitionVersion must be positive");
        }
    }

    @Override
    public int compareTo(ProjectionDefinitionVersion other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
