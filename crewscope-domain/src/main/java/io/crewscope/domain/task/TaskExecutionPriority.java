package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;

/** Scheduler priority independent from the user-visible WorkItem planning priority. */
public record TaskExecutionPriority(int value) implements Comparable<TaskExecutionPriority> {

    public static final int MIN_VALUE = 0;
    public static final int MAX_VALUE = 100;
    public static final TaskExecutionPriority NORMAL = new TaskExecutionPriority(50);

    public TaskExecutionPriority {
        if (value < MIN_VALUE || value > MAX_VALUE) {
            throw new DomainValidationException(
                    "taskExecution.priority", "must be between 0 and 100");
        }
    }

    @Override
    public int compareTo(TaskExecutionPriority other) {
        return Integer.compare(value, java.util.Objects.requireNonNull(other, "other").value);
    }
}
