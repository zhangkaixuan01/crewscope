package io.crewscope.domain.action;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Server-computed digest of an ordered action graph. */
public record ActionBundleDigest(TaskFactHash value) {

    public ActionBundleDigest {
        value = Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
