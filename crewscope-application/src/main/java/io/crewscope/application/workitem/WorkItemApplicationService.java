package io.crewscope.application.workitem;

import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.shared.time.TimeProvider;
import java.util.Objects;

public final class WorkItemApplicationService {

    private final WorkItemRepository repository;
    private final TimeProvider timeProvider;

    public WorkItemApplicationService(
            WorkItemRepository repository, TimeProvider timeProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public WorkItem create(
            WorkItemCommandContext context, CreateWorkItemCommand command) {
        WorkItemCommandContext trustedContext = Objects.requireNonNull(context, "context");
        CreateWorkItemCommand requiredCommand = Objects.requireNonNull(command, "command");
        WorkItem workItem = WorkItem.create(
                WorkItemId.generate(),
                new WorkItemScope(
                        trustedContext.organizationId(),
                        trustedContext.teamId(),
                        trustedContext.workspaceId(),
                        requiredCommand.projectId()),
                new WorkItemKey(requiredCommand.key()),
                requiredCommand.title(),
                trustedContext.actorId(),
                timeProvider.now());
        return repository.create(workItem);
    }
}
