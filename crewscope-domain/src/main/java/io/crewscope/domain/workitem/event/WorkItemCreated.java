package io.crewscope.domain.workitem.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemStatus;
import java.util.Objects;
import java.util.UUID;

/** Version 1 business payload emitted after a WorkItem is created. */
public record WorkItemCreated(
        UUID projectId, String itemKey, String title, WorkItemStatus status)
        implements DomainEvent {

    public WorkItemCreated {
        projectId = AggregateId.requireValue(projectId, "WorkItemCreated.projectId");
        itemKey = new WorkItemKey(itemKey).value();
        if (title == null || title.isBlank()) {
            throw new DomainValidationException("workItemCreated.title", "must not be blank");
        }
        title = title.strip();
        if (title.length() > WorkItem.MAX_TITLE_LENGTH) {
            throw new DomainValidationException(
                    "workItemCreated.title",
                    "must contain at most " + WorkItem.MAX_TITLE_LENGTH + " characters");
        }
        status = Objects.requireNonNull(status, "status");
    }

    /** Creates the event payload from the committed aggregate snapshot. */
    public static WorkItemCreated from(WorkItem workItem) {
        WorkItem source = Objects.requireNonNull(workItem, "workItem");
        return new WorkItemCreated(
                source.scope().projectId().value(),
                source.key().value(),
                source.title(),
                source.status());
    }
}
