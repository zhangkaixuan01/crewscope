package io.crewscope.application.workitem;

import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.List;
import java.util.Optional;

/** Persistence Port for immutable WorkItem comments. */
public interface WorkItemCommentRepository {

    WorkItemComment create(WorkItemComment comment);

    Optional<WorkItemComment> findById(OrganizationId organizationId, WorkItemCommentId id);

    List<WorkItemComment> findByWorkItem(
            OrganizationId organizationId, WorkItemId workItemId);
}
