package io.crewscope.domain.review;

import io.crewscope.domain.coding.DiffPath;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Canonical changed-file line range cited by a Finding. */
public record FindingLocation(DiffPath path, int startLine, int endLine)
        implements Comparable<FindingLocation> {

    public FindingLocation {
        path = Objects.requireNonNull(path, "path");
        if (startLine < 1 || endLine < startLine) {
            throw new DomainValidationException(
                    "reviewFinding.location", "must be a positive ordered line range");
        }
    }

    public FindingLocation(String path, int startLine, int endLine) {
        this(new DiffPath(path), startLine, endLine);
    }

    @Override
    public int compareTo(FindingLocation other) {
        FindingLocation required = Objects.requireNonNull(other, "other");
        int pathOrder = path.compareTo(required.path);
        if (pathOrder != 0) {
            return pathOrder;
        }
        int startOrder = Integer.compare(startLine, required.startLine);
        return startOrder != 0 ? startOrder : Integer.compare(endLine, required.endLine);
    }
}
