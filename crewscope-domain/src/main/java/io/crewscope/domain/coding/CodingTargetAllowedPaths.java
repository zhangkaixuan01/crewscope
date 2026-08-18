package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Canonical repository-relative path roots captured by one CodingTargetSnapshot. */
public record CodingTargetAllowedPaths(List<String> values) {

    public static final int MAX_PATHS = 200;
    public static final int MAX_PATH_LENGTH = 1_024;

    private static final Comparator<String> CODE_POINT_ORDER =
            CodingTargetAllowedPaths::compareCodePoints;

    public CodingTargetAllowedPaths {
        Collection<String> supplied = Objects.requireNonNull(values, "values");
        if (supplied.isEmpty() || supplied.size() > MAX_PATHS) {
            throw new DomainValidationException(
                    "codingTargetSnapshot.allowedPaths", "must contain 1 to 200 paths");
        }
        TreeSet<String> normalized = new TreeSet<>(CODE_POINT_ORDER);
        supplied.forEach(value -> normalized.add(normalize(value)));
        List<String> compacted = new ArrayList<>();
        for (String candidate : normalized) {
            if (compacted.stream().noneMatch(parent -> contains(parent, candidate))) {
                compacted.add(candidate);
            }
        }
        values = List.copyOf(compacted);
    }

    public static CodingTargetAllowedPaths of(String... values) {
        return new CodingTargetAllowedPaths(List.of(values));
    }

    /** Returns true when a repository-relative path is covered by one captured root. */
    public boolean allows(String candidate) {
        String normalized = normalize(candidate);
        return values.stream().anyMatch(parent -> contains(parent, normalized));
    }

    /** Returns true when every target root is equal to or below one root in this set. */
    public boolean containsAll(CodingTargetAllowedPaths target) {
        CodingTargetAllowedPaths required = Objects.requireNonNull(target, "target");
        return required.values.stream()
                .allMatch(candidate -> values.stream().anyMatch(parent -> contains(parent, candidate)));
    }

    private static boolean contains(String parent, String candidate) {
        return parent.equals(".")
                || parent.equals(candidate)
                || candidate.startsWith(parent + "/");
    }

    private static String normalize(String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > MAX_PATH_LENGTH
                || value.startsWith("/")
                || value.startsWith("\\")
                || value.contains("\\")
                || hasWindowsDrivePrefix(value)
                || value.chars().anyMatch(character -> character == 0 || character < 0x20)) {
            throw invalidPath();
        }
        if (value.equals(".")) {
            return value;
        }
        String[] components = value.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw invalidPath();
            }
        }
        return String.join("/", components);
    }

    private static boolean hasWindowsDrivePrefix(String value) {
        return value.length() >= 2
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':';
    }

    private static DomainValidationException invalidPath() {
        return new DomainValidationException(
                "codingTargetSnapshot.allowedPaths",
                "must contain canonical repository-relative paths without traversal");
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
}
