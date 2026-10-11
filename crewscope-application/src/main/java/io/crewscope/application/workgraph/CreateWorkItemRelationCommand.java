package io.crewscope.application.workgraph;

import io.crewscope.domain.workgraph.WorkItemRelationType;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Objects;

/**
 * Relates two WorkItems in the submitted direction; DEPENDS_ON is normalized by the aggregate
 * into the canonical blocking edge before storage (ADR-033).
 */
public record CreateWorkItemRelationCommand(
        WorkItemRelationType type, WorkItemId from, WorkItemId to) {

    public CreateWorkItemRelationCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
