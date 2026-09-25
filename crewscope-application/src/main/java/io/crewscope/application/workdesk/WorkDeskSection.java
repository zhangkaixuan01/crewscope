package io.crewscope.application.workdesk;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One prioritized WorkDesk section with an explicit truncation signal.
 *
 * <p>{@code total} is the section's true full-set count, not its page length; {@code nextPosition}
 * is the keyset tail of this page — present exactly when more rows follow it.
 */
public record WorkDeskSection(
    String key,
    String title,
    int priority,
    int total,
    boolean truncated,
    List<WorkDeskItem> items,
    Optional<WorkDeskSectionPosition> nextPosition) {

    /** The pre-pagination shape; the page carries every row and no continuation. */
    public WorkDeskSection(
        String key, String title, int priority, int total, boolean truncated, List<WorkDeskItem> items) {
        this(key, title, priority, total, truncated, items, Optional.empty());
    }

    public WorkDeskSection {
        if (key == null || key.isBlank() || title == null || title.isBlank()) {
            throw new IllegalArgumentException("WorkDesk section labels must not be blank");
        }
        if (priority < 1 || total < 0) throw new IllegalArgumentException("invalid WorkDesk section counters");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.size() > total) throw new IllegalArgumentException("section items exceed total");
        nextPosition = Objects.requireNonNull(nextPosition, "nextPosition");
    }
}
