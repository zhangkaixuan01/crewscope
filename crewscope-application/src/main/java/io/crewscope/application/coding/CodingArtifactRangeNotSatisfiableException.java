package io.crewscope.application.coding;

/** Stable application error carrying only the safe current Artifact size. */
public final class CodingArtifactRangeNotSatisfiableException extends RuntimeException {

    private final long totalSize;

    public CodingArtifactRangeNotSatisfiableException(long totalSize) {
        super("Coding Artifact range is outside the available content");
        if (totalSize < 0) {
            throw new IllegalArgumentException("totalSize must not be negative");
        }
        this.totalSize = totalSize;
    }

    public long totalSize() {
        return totalSize;
    }
}
