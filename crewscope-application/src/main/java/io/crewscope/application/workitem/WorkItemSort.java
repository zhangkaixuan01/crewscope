package io.crewscope.application.workitem;

import java.util.Optional;

/**
 * Server-owned WorkItem list orderings (M9b-A06).
 *
 * <p>Every ordering ends with the row ID in the same direction as its primary key, so two rows that
 * compare equal on the primary value still have one stable total order — the tie-breaker a page
 * cursor needs before "no duplicates, no gaps" can be promised.
 */
public enum WorkItemSort {
    /** Most recently updated first. The default. */
    UPDATED_AT,
    /** Highest planning priority first; ties fall through to the ID. */
    PRIORITY,
    /** Earliest due time first; rows without a due time sort last. */
    DUE_AT,
    /** Most recently created first. */
    CREATED_AT;

    /**
     * Parses a client-supplied sort name in the wire spelling {@code updatedAt}, {@code dueAt}, … —
     * the one the browser contract sends. Absent or blank yields empty (the caller applies the
     * default); an unknown name also yields empty, and the HTTP layer turns that into an explicit
     * invalid-request rejection rather than silently reordering a list the member reads.
     */
    public static Optional<WorkItemSort> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return switch (value.strip()) {
            case "updatedAt" -> Optional.of(UPDATED_AT);
            case "priority" -> Optional.of(PRIORITY);
            case "dueAt" -> Optional.of(DUE_AT);
            case "createdAt" -> Optional.of(CREATED_AT);
            default -> Optional.empty();
        };
    }

    /** Whether the primary key orders newest/highest first; {@link #DUE_AT} is the ascending one. */
    public boolean primaryDescending() {
        return this != DUE_AT;
    }
}
