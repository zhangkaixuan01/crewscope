package io.crewscope.application.task;

/** Read Port for all scope-closed, keyset-paginated Task association directions. */
public interface TaskAssociationRepository {

    /** Loads Tasks and their current attempts with one bounded database query. */
    TaskAssociationPage findTasks(TaskAssociationQuery query);

    /** Loads only Conversations currently discoverable by the supplied member Principal. */
    TaskConversationAssociationPage findVisibleConversations(
            TaskConversationAssociationQuery query);
}
