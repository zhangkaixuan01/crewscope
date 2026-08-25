package io.crewscope.domain.projection;

/** Positive generation number of one rebuildable projection. */
public record ProjectionGeneration(long value) implements Comparable<ProjectionGeneration> {

    public static final ProjectionGeneration FIRST = new ProjectionGeneration(1);

    public ProjectionGeneration {
        if (value < 1) {
            throw new IllegalArgumentException("ProjectionGeneration must be positive");
        }
    }

    /** Returns the next generation and fails closed on numeric exhaustion. */
    public ProjectionGeneration next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("ProjectionGeneration is exhausted");
        }
        return new ProjectionGeneration(value + 1);
    }

    @Override
    public int compareTo(ProjectionGeneration other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
