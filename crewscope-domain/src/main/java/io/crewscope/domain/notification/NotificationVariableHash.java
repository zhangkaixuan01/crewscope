package io.crewscope.domain.notification;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Canonical hash of validated template variables without exposing their values. */
public record NotificationVariableHash(TaskFactHash value) {

    public NotificationVariableHash {
        value = Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
