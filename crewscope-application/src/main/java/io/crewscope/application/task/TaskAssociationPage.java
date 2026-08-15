package io.crewscope.application.task;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded association page that preserves historical and terminal Tasks. */
public record TaskAssociationPage(
        List<TaskAssociationItem> items, Optional<TaskAssociationCursor> nextCursor) {

    public TaskAssociationPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
