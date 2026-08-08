package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable WorkItem resource relations. */
public interface WorkItemResourceLinkRepository {

    WorkItemResourceLink create(WorkItemResourceLink link);

    Optional<WorkItemResourceLink> findById(
            OrganizationId organizationId, WorkItemResourceLinkId id);

    List<WorkItemResourceLink> findByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId);
}
