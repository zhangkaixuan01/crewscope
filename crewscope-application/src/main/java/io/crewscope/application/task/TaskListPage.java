package io.crewscope.application.task;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable page returned by the Task collection read Port. */
public record TaskListPage(List<TaskListItem> items, Optional<TaskListCursor> nextCursor) {

    public TaskListPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
