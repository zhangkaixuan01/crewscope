package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import java.util.Objects;

public final class WorkItemApplicationService {

    private final WorkItemRepository repository;

    public WorkItemApplicationService(WorkItemRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public WorkItem create(CreateWorkItemCommand command) {
        Objects.requireNonNull(command, "command");
        WorkItem workItem = WorkItem.create(
                WorkItemId.generate(), new WorkItemKey(command.key()), command.title());
        return repository.save(workItem);
    }
}
