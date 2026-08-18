package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Canonical repository-relative path with locale-independent Unicode code-point ordering. */
public record DiffPath(String value) implements Comparable<DiffPath> {

    public DiffPath {
        CodingTargetAllowedPaths canonical = CodingTargetAllowedPaths.of(value);
        if (".".equals(value)
                || canonical.values().size() != 1
                || !canonical.values().get(0).equals(value)) {
            throw new DomainValidationException(
                    "diffFileEntry.path", "must be one canonical repository-relative path");
        }
    }

    @Override
    public int compareTo(DiffPath other) {
        return compareCodePoints(value, java.util.Objects.requireNonNull(other, "other").value);
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftPoint = left.codePointAt(leftOffset);
            int rightPoint = right.codePointAt(rightOffset);
            if (leftPoint != rightPoint) {
                return Integer.compare(leftPoint, rightPoint);
            }
            leftOffset += Character.charCount(leftPoint);
            rightOffset += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }

    public boolean isWithin(CodingTargetAllowedPaths allowedPaths) {
        return java.util.Objects.requireNonNull(allowedPaths, "allowedPaths").allows(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
