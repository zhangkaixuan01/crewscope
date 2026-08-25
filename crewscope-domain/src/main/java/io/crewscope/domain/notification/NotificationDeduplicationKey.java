package io.crewscope.domain.notification;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Stable provider idempotency key for one explicitly authorized send. */
public record NotificationDeduplicationKey(TaskFactHash value) {
    public NotificationDeduplicationKey {
        value = Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
