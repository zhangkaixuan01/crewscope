package io.crewscope.domain.projection;

/** Monotonic lease token that rejects work started before a lifecycle transition. */
public record ProjectionFencingToken(long value) {

    public static final ProjectionFencingToken INITIAL = new ProjectionFencingToken(1);

    public ProjectionFencingToken {
        if (value < 1) {
            throw new IllegalArgumentException("ProjectionFencingToken must be positive");
        }
    }

    public ProjectionFencingToken next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("ProjectionFencingToken is exhausted");
        }
        return new ProjectionFencingToken(value + 1);
    }
}
