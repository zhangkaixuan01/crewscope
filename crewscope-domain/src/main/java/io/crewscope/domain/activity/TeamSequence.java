package io.crewscope.domain.activity;

/** Positive, projection-generation-local ordering position in one Team Activity stream. */
public record TeamSequence(long value) implements Comparable<TeamSequence> {

    public static final TeamSequence FIRST = new TeamSequence(1);

    public TeamSequence {
        if (value < 1) {
            throw new IllegalArgumentException("TeamSequence must be positive");
        }
    }

    /** Returns the immediately following stream position. */
    public TeamSequence next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("TeamSequence is exhausted");
        }
        return new TeamSequence(value + 1);
    }

    public boolean isAfter(TeamSequence other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(TeamSequence other) {
        return Long.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
