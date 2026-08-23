package io.crewscope.domain.action;

import java.util.Objects;

/** Explicit predecessor edge inside one ActionBundle. */
public record ActionDependency(PlannedActionId predecessorActionId) {

    public ActionDependency {
        predecessorActionId = Objects.requireNonNull(predecessorActionId, "predecessorActionId");
    }
}
