package io.crewscope.application.workgraph;

import io.crewscope.domain.workgraph.WorkItemRelationId;
import java.util.Objects;

/** Deletes one stored relation edge; the removal fact re-enters the event stream. */
public record RemoveWorkItemRelationCommand(WorkItemRelationId relationId) {

    public RemoveWorkItemRelationCommand {
        Objects.requireNonNull(relationId, "relationId");
    }
}
