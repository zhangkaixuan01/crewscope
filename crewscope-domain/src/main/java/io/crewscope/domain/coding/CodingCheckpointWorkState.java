package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.task.TaskFactHash;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded Agent Plan/Todo state retained across compaction without becoming execution truth. */
public record CodingCheckpointWorkState(
        String planMarkdown, List<CodingCheckpointTodo> todos, TaskFactHash contentHash) {

    public CodingCheckpointWorkState(String planMarkdown, List<CodingCheckpointTodo> todos) {
        this(planMarkdown, todos, null);
    }

    public CodingCheckpointWorkState {
        if (planMarkdown == null || planMarkdown.isBlank() || planMarkdown.length() > 50_000) {
            throw new DomainValidationException(
                    "codingCheckpoint.planMarkdown", "must be non-blank and bounded");
        }
        planMarkdown = planMarkdown.strip();
        todos = List.copyOf(Objects.requireNonNull(todos, "todos"));
        if (todos.size() > 200
                || todos.stream().anyMatch(Objects::isNull)
                || new HashSet<>(todos.stream().map(CodingCheckpointTodo::key).toList()).size()
                        != todos.size()) {
            throw new DomainValidationException(
                    "codingCheckpoint.todos", "must contain at most 200 uniquely keyed items");
        }
        TaskFactHash calculated = calculateHash(planMarkdown, todos);
        if (contentHash != null && !contentHash.equals(calculated)) {
            throw new DomainValidationException(
                    "codingCheckpoint.workStateHash", "must match canonical Plan/Todo state");
        }
        contentHash = calculated;
    }

    private static TaskFactHash calculateHash(
            String planMarkdown, List<CodingCheckpointTodo> todos) {
        StringBuilder canonical = new StringBuilder("coding-work-state-v1");
        append(canonical, planMarkdown);
        append(canonical, Integer.toString(todos.size()));
        todos.forEach(todo -> {
            append(canonical, todo.key());
            append(canonical, todo.status().name());
            append(canonical, todo.summary());
        });
        return TaskFactHash.sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }
}
