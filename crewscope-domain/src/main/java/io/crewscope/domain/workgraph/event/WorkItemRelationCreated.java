package io.crewscope.domain.workgraph.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationType;
import java.util.Objects;
import java.util.UUID;

/**
 * Version 1 payload emitted when a WorkItemRelation is committed. Carries both project ids so
 * audit and later projections can surface cross-WorkProject provenance (ADR-033).
 */
public record WorkItemRelationCreated(
        UUID relationId,
        UUID srcWorkItemId,
        UUID dstWorkItemId,
        WorkItemRelationType kind,
        UUID srcProjectId,
        UUID dstProjectId)
        implements DomainEvent {

    public WorkItemRelationCreated {
        relationId = AggregateId.requireValue(relationId, "WorkItemRelationCreated.relationId");
        srcWorkItemId = AggregateId.requireValue(srcWorkItemId, "WorkItemRelationCreated.srcWorkItemId");
        dstWorkItemId = AggregateId.requireValue(dstWorkItemId, "WorkItemRelationCreated.dstWorkItemId");
        kind = Objects.requireNonNull(kind, "kind");
        if (kind == WorkItemRelationType.DEPENDS_ON) {
            throw new DomainValidationException(
                    "workItemRelationCreated.kind", "must be the stored kind, never DEPENDS_ON");
        }
        srcProjectId = AggregateId.requireValue(srcProjectId, "WorkItemRelationCreated.srcProjectId");
        dstProjectId = AggregateId.requireValue(dstProjectId, "WorkItemRelationCreated.dstProjectId");
    }

    /** Creates the committed-relation fact from the persisted aggregate. */
    public static WorkItemRelationCreated from(WorkItemRelation relation) {
        WorkItemRelation source = Objects.requireNonNull(relation, "relation");
        return new WorkItemRelationCreated(
                source.id().value(),
                source.src().workItemId().value(),
                source.dst().workItemId().value(),
                source.kind(),
                source.src().scope().projectId().value(),
                source.dst().scope().projectId().value());
    }
}
