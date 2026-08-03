package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import java.util.Optional;

public interface WorkItemRepository {

    WorkItem save(WorkItem workItem);

    Optional<WorkItem> findById(WorkItemId id);
}
