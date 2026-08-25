package io.crewscope.domain.notification;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Canonical digest binding all policy-preauthorization coordinates. */
public record NotificationAuthorizationDigest(TaskFactHash value) {
    public NotificationAuthorizationDigest {
        value = Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
