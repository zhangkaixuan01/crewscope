package io.crewscope.application.task;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskId;
import java.util.Objects;

/** Stable descending keyset position for the member-facing Task collection. */
public record TaskListCursor(UtcTimestamp updatedAt, TaskId id) {

    public TaskListCursor {
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        id = Objects.requireNonNull(id, "id");
    }
}
