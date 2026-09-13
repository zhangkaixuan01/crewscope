package io.crewscope.domain.review;

import io.crewscope.domain.coding.DiffGeneration;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.RuntimeContentHash;
import java.util.Objects;

/** Immutable, hash-closed location in one Review Diff snapshot. */
public record ReviewCommentAnchor(
        FindingLocation location,
        ReviewCommentSide side,
        String hunkHeader,
        RuntimeContentHash lineContentHash,
        DiffGeneration diffGeneration) {

    public static final int MAX_HUNK_HEADER_LENGTH = 1_000;

    public ReviewCommentAnchor {
        location = Objects.requireNonNull(location, "location");
        if (location.startLine() != location.endLine()) {
            throw new DomainValidationException(
                    "reviewCommentAnchor.location", "must identify exactly one line");
        }
        side = Objects.requireNonNull(side, "side");
        if (hunkHeader == null || hunkHeader.isBlank()) {
            throw new DomainValidationException("reviewCommentAnchor.hunkHeader", "must not be blank");
        }
        hunkHeader = hunkHeader.strip();
        if (hunkHeader.length() > MAX_HUNK_HEADER_LENGTH) {
            throw new DomainValidationException("reviewCommentAnchor.hunkHeader", "is too long");
        }
        lineContentHash = Objects.requireNonNull(lineContentHash, "lineContentHash");
        diffGeneration = Objects.requireNonNull(diffGeneration, "diffGeneration");
    }
}
