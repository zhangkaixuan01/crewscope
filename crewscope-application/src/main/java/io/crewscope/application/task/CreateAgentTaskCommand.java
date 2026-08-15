package io.crewscope.application.task;

import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.task.TaskBrief;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** User-approved inputs for delegating one current WorkItem to an assigned Agent. */
public record CreateAgentTaskCommand(
        TaskBrief brief,
        AgentProfileId executorAgentProfileId,
        Optional<TaskConversationSource> conversationSource,
        Set<ProviderBindingId> providerBindingIds,
        long expectedWorkItemVersion) {

    public CreateAgentTaskCommand {
        brief = Objects.requireNonNull(brief, "brief");
        if (brief.acceptanceCriteria().isEmpty()) {
            throw new IllegalArgumentException("at least one acceptance criterion is required");
        }
        executorAgentProfileId = Objects.requireNonNull(
                executorAgentProfileId, "executorAgentProfileId");
        conversationSource = Objects.requireNonNull(conversationSource, "conversationSource");
        providerBindingIds = Set.copyOf(Objects.requireNonNull(
                providerBindingIds, "providerBindingIds"));
        if (providerBindingIds.size() > 200) {
            throw new IllegalArgumentException("providerBindingIds must not exceed 200 values");
        }
        if (expectedWorkItemVersion < 0) {
            throw new IllegalArgumentException("expectedWorkItemVersion must not be negative");
        }
    }
}
