package io.crewscope.application.workitem;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Scope-bound keyset position for one WorkItem list ordering (M9b-A06).
 *
 * <p>The primary key component matches the scope's ordering: a time for the time-based orderings
 * and for the non-null segment of {@link WorkItemSort#DUE_AT}, or a rank for {@link
 * WorkItemSort#PRIORITY}. The row ID is always present and always breaks ties. For {@code DUE_AT} a
 * cursor with no primary component means the traversal has entered the trailing null-due-time
 * segment, which sorts last.
 */
public record WorkItemCursor(
        WorkItemCursorScope scope,
        Optional<UtcTimestamp> primaryTime,
        OptionalInt primaryRank,
        WorkItemId id) {

    public WorkItemCursor {
        scope = Objects.requireNonNull(scope, "scope");
        primaryTime = Objects.requireNonNull(primaryTime, "primaryTime");
        primaryRank = Objects.requireNonNull(primaryRank, "primaryRank");
        id = Objects.requireNonNull(id, "id");
        if (primaryTime.isPresent() && primaryRank.isPresent()) {
            throw new IllegalArgumentException(
                    "WorkItemCursor cannot carry both a primary time and a primary rank");
        }
        if (primaryRank.isPresent() && scope.sort() != WorkItemSort.PRIORITY) {
            throw new IllegalArgumentException(
                    "WorkItemCursor carries a primary rank only for the PRIORITY ordering");
        }
        if (primaryTime.isEmpty() && primaryRank.isEmpty() && scope.sort() != WorkItemSort.DUE_AT) {
            throw new IllegalArgumentException(
                    "WorkItemCursor without a primary component is only valid inside the"
                            + " DUE_AT null-due-time segment");
        }
    }

    /** Fails closed when an opaque cursor is replayed on another query, member, sort or filter. */
    public WorkItemCursor requireScope(WorkItemCursorScope expectedScope) {
        if (!scope.equals(Objects.requireNonNull(expectedScope, "expectedScope"))) {
            throw new IllegalArgumentException(
                    "WorkItem cursor does not belong to the requested scope, sort and filter");
        }
        return this;
    }
}
