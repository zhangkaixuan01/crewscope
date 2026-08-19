package io.crewscope.application.artifact;

/** Exact half-open byte range requested from an immutable Artifact. */
public record ArtifactByteRange(long startInclusive, long endExclusive) {

    public ArtifactByteRange {
        if (startInclusive < 0 || endExclusive <= startInclusive) {
            throw new IllegalArgumentException(
                    "Artifact byte range must be non-empty and use non-negative offsets");
        }
    }

    public long length() {
        return endExclusive - startInclusive;
    }

    /** Ensures that the exact range exists in the current immutable representation. */
    public void requireWithin(long totalSize) {
        if (totalSize < 0 || startInclusive >= totalSize || endExclusive > totalSize) {
            throw new ArtifactStoreException(
                    ArtifactStoreError.RANGE_NOT_SATISFIABLE,
                    "Artifact byte range is outside the available content");
        }
    }
}
