package io.crewscope.domain.action;

import io.crewscope.domain.task.TaskFactHash;
import java.util.Objects;

/** Server-computed digest of one exact action and every authority coordinate it depends on. */
public record ActionDigest(TaskFactHash value) {

    public ActionDigest {
        value = Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
