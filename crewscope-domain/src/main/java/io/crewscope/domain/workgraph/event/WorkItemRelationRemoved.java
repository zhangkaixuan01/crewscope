package io.crewscope.domain.workgraph.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 payload emitted when a stored WorkItemRelation is deleted, including the
 * cascade-of-all-edges path reserved for the future WorkItem removal protocol (ADR-033).
 */
public record WorkItemRelationRemoved(
        UUID relationId,
        UUID srcWorkItemId,
        UUID dstWorkItemId,
        WorkItemRelationType kind,
        UUID srcProjectId,
        UUID dstProjectId)
        implements DomainEvent {

    public WorkItemRelationRemoved {
        relationId = AggregateId.requireValue(relationId, "WorkItemRelationRemoved.relationId");
        srcWorkItemId = AggregateId.requireValue(srcWorkItemId, "WorkItemRelationRemoved.srcWorkItemId");
        dstWorkItemId = AggregateId.requireValue(dstWorkItemId, "WorkItemRelationRemoved.dstWorkItemId");
        kind = Objects.requireNonNull(kind, "kind");
        if (kind == WorkItemRelationType.DEPENDS_ON) {
            throw new DomainValidationException(
                    "workItemRelationRemoved.kind", "must be the stored kind, never DEPENDS_ON");
        }
        srcProjectId = AggregateId.requireValue(srcProjectId, "WorkItemRelationRemoved.srcProjectId");
        dstProjectId = AggregateId.requireValue(dstProjectId, "WorkItemRelationRemoved.dstProjectId");
    }

    /** Creates the removal fact from the edge as it existed before deletion. */
    public static WorkItemRelationRemoved from(WorkItemRelation relation) {
        WorkItemRelation source = Objects.requireNonNull(relation, "relation");
        return new WorkItemRelationRemoved(
                source.id().value(),
                source.src().workItemId().value(),
                source.dst().workItemId().value(),
                source.kind(),
                source.src().scope().projectId().value(),
                source.dst().scope().projectId().value());
    }
}
