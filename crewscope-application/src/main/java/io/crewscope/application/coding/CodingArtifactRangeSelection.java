package io.crewscope.application.coding;

import io.crewscope.application.artifact.ArtifactByteRange;
import java.util.Optional;

/** Transport-neutral whole, closed, open-ended or suffix byte selection. */
public record CodingArtifactRangeSelection(Mode mode, long first, long second) {

    public CodingArtifactRangeSelection {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        switch (mode) {
            case WHOLE -> {
                if (first != 0 || second != 0) {
                    throw new IllegalArgumentException("Whole selection must not carry coordinates");
                }
            }
            case BETWEEN -> {
                if (first < 0 || second <= first) {
                    throw new IllegalArgumentException("Between selection must be a non-empty range");
                }
            }
            case FROM -> {
                if (first < 0 || second != 0) {
                    throw new IllegalArgumentException("From selection is invalid");
                }
            }
            case SUFFIX -> {
                if (first <= 0 || second != 0) {
                    throw new IllegalArgumentException("Suffix selection must be positive");
                }
            }
        }
    }

    public static CodingArtifactRangeSelection whole() {
        return new CodingArtifactRangeSelection(Mode.WHOLE, 0, 0);
    }

    public static CodingArtifactRangeSelection between(long startInclusive, long endExclusive) {
        return new CodingArtifactRangeSelection(Mode.BETWEEN, startInclusive, endExclusive);
    }

    public static CodingArtifactRangeSelection from(long startInclusive) {
        return new CodingArtifactRangeSelection(Mode.FROM, startInclusive, 0);
    }

    public static CodingArtifactRangeSelection suffix(long length) {
        return new CodingArtifactRangeSelection(Mode.SUFFIX, length, 0);
    }

    public Optional<ArtifactByteRange> resolve(long totalSize) {
        if (totalSize < 0) {
            throw new IllegalArgumentException("totalSize must not be negative");
        }
        if (mode == Mode.WHOLE) {
            return Optional.empty();
        }
        if (totalSize == 0) {
            throw new CodingArtifactRangeNotSatisfiableException(totalSize);
        }
        long start;
        long end;
        switch (mode) {
            case BETWEEN -> {
                start = first;
                // RFC 9110 treats an end position beyond the selected representation as its
                // final byte. The same rule makes offset/limit return a short final page.
                end = Math.min(second, totalSize);
            }
            case FROM -> {
                start = first;
                end = totalSize;
            }
            case SUFFIX -> {
                start = Math.max(0, totalSize - first);
                end = totalSize;
            }
            default -> throw new IllegalStateException("Whole selection was already handled");
        }
        if (start >= totalSize || end <= start) {
            throw new CodingArtifactRangeNotSatisfiableException(totalSize);
        }
        return Optional.of(new ArtifactByteRange(start, end));
    }

    public boolean ranged() {
        return mode != Mode.WHOLE;
    }

    public enum Mode {
        WHOLE,
        BETWEEN,
        FROM,
        SUFFIX
    }
}
