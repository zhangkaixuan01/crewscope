package io.crewscope.application.workgraph;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workgraph.WorkItemRelation;
import io.crewscope.domain.workgraph.WorkItemRelationId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for WorkItemRelation edge writes and lookups (ADR-033). */
public interface WorkItemRelationRepository {

    WorkItemRelation create(WorkItemRelation relation);

    Optional<WorkItemRelation> findById(OrganizationId organizationId, WorkItemRelationId id);

    /** Returns the edge stored in exactly this direction, if any. */
    Optional<WorkItemRelation> findByStoredEndpoints(
            OrganizationId organizationId, WorkItemId src, WorkItemId dst);

    /** Returns every edge touching the endpoint, in either direction. */
    List<WorkItemRelation> findByEndpoint(OrganizationId organizationId, WorkItemId endpoint);

    /** Deletes one committed edge; returns the removed fact for event emission, if it existed. */
    Optional<WorkItemRelation> removeById(OrganizationId organizationId, WorkItemRelationId id);

    /**
     * Cascade primitive (ADR-033): deletes every edge touching the WorkItem in the same
     * transaction and returns the removed facts for event emission. Reserved for the future
     * WorkItem removal protocol — until that lands, edge lifecycle is owned by edge removal.
     */
    List<WorkItemRelation> removeByWorkItem(OrganizationId organizationId, WorkItemId workItem);
}
