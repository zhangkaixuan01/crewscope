package io.crewscope.application.task;

import io.crewscope.domain.task.Task;
import io.crewscope.domain.workitem.WorkItem;
import java.util.Objects;

/** Task-side association result after independently authorizing its WorkItem and Conversations. */
public record TaskAssociationDetails(
        Task task,
        WorkItem workItem,
        TaskConversationAssociationPage conversations) {

    public TaskAssociationDetails {
        task = Objects.requireNonNull(task, "task");
        workItem = Objects.requireNonNull(workItem, "workItem");
        conversations = Objects.requireNonNull(conversations, "conversations");
    }
}
