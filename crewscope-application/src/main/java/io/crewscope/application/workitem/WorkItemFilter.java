package io.crewscope.application.workitem;

import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normalized multi-value WorkItem list filter (M9b-A06).
 *
 * <p>Empty sets mean "no restriction", matching the single-status parameter this record replaces:
 * the previous {@code Optional<WorkItemStatus>} becomes {@code statuses} with at most one value.
 */
public record WorkItemFilter(
        Set<WorkItemStatus> statuses,
        Set<WorkItemType> types,
        Set<WorkItemPriority> priorities,
        Optional<ResponsibilityRole> responsibilityRole) {

    public static final int MAX_VALUES_PER_FILTER = 8;

    public static final WorkItemFilter ALL =
            new WorkItemFilter(Set.of(), Set.of(), Set.of(), Optional.empty());

    public WorkItemFilter {
        statuses = copyBounded(statuses, "statuses");
        types = copyBounded(types, "types");
        priorities = copyBounded(priorities, "priorities");
        responsibilityRole = Objects.requireNonNull(responsibilityRole, "responsibilityRole");
    }

    /** Single-status convenience preserving the previous call shape. */
    public static WorkItemFilter ofStatus(WorkItemStatus status) {
        return new WorkItemFilter(
                Set.of(Objects.requireNonNull(status, "status")),
                Set.of(),
                Set.of(),
                Optional.empty());
    }

    /** Computes a stable scope fingerprint without exposing raw filter data in a cursor token. */
    public WorkItemFilterFingerprint fingerprint() {
        String canonical = "work-item-filter-v1"
                + "\nstatuses=" + enumNames(statuses)
                + "\ntypes=" + enumNames(types)
                + "\npriorities=" + enumNames(priorities)
                + "\nresponsibilityRole="
                + responsibilityRole.map(Enum::name).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new WorkItemFilterFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static <T extends Enum<?>> Set<T> copyBounded(Set<T> values, String name) {
        Set<T> copy = Set.copyOf(Objects.requireNonNull(values, name));
        if (copy.size() > MAX_VALUES_PER_FILTER) {
            throw new DomainValidationException(
                    "workItemQuery." + name,
                    "must contain at most " + MAX_VALUES_PER_FILTER + " values");
        }
        return copy;
    }

    private static String enumNames(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
