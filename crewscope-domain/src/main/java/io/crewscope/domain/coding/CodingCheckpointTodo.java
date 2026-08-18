package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** One stable Agent todo captured before compaction or process handoff. */
public record CodingCheckpointTodo(String key, CodingTodoStatus status, String summary) {

    public CodingCheckpointTodo {
        if (key == null || !key.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")) {
            throw new DomainValidationException(
                    "codingCheckpoint.todo.key", "must be a stable bounded key");
        }
        status = Objects.requireNonNull(status, "status");
        if (summary == null || summary.isBlank() || summary.length() > 1_000) {
            throw new DomainValidationException(
                    "codingCheckpoint.todo.summary", "must be non-blank and bounded");
        }
        summary = summary.strip();
    }
}
