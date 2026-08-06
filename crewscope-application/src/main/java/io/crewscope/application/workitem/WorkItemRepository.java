package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/** Persistence Port for the WorkItem aggregate, scoped at every tenant-sensitive operation. */
public interface WorkItemRepository {

    /** Inserts a new aggregate at version zero. */
    WorkItem create(WorkItem workItem);

    /** Commits one domain mutation using the aggregate's previous version as the lock predicate. */
    WorkItem update(WorkItem workItem);

    /** Finds one aggregate only inside the supplied Organization boundary. */
    Optional<WorkItem> findById(OrganizationId organizationId, WorkItemId id);

    /** Returns one deterministic keyset-paginated slice. */
    WorkItemPage findPage(WorkItemQuery query);
}
