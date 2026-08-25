package io.crewscope.domain.notification;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable variable set accepted by one exact fixed-template schema. */
public final class NotificationVariables {

    private final Map<String, String> values;
    private final NotificationVariableHash hash;

    NotificationVariables(Map<String, String> values) {
        TreeMap<String, String> sorted = new TreeMap<>(Objects.requireNonNull(values, "values"));
        this.values = Map.copyOf(sorted);
        StringBuilder canonical = new StringBuilder("notification-variables-v1");
        sorted.forEach((key, value) -> append(canonical, key, value));
        this.hash = new NotificationVariableHash(TaskFactHash.sha256(canonical.toString()));
    }

    public Map<String, String> values() {
        return values;
    }

    public NotificationVariableHash hash() {
        return hash;
    }

    private static void append(StringBuilder target, String... values) {
        for (String value : values) {
            target.append('|').append(value.length()).append(':').append(value);
        }
    }
}
