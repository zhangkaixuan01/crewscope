package io.crewscope.application.task;

import io.crewscope.application.coding.CreateCodingTargetCommand;
import io.crewscope.domain.agent.AgentConfigurationRevision;
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
        Optional<AgentConfigurationRevision> agentConfigurationRevision,
        Optional<TaskConversationSource> conversationSource,
        Set<ProviderBindingId> providerBindingIds,
        Optional<CreateCodingTargetCommand> codingTarget,
        long expectedWorkItemVersion) {

    public CreateAgentTaskCommand {
        brief = Objects.requireNonNull(brief, "brief");
        if (brief.acceptanceCriteria().isEmpty()) {
            throw new IllegalArgumentException("at least one acceptance criterion is required");
        }
        executorAgentProfileId = Objects.requireNonNull(
                executorAgentProfileId, "executorAgentProfileId");
        agentConfigurationRevision = Objects.requireNonNull(
                agentConfigurationRevision, "agentConfigurationRevision");
        conversationSource = Objects.requireNonNull(conversationSource, "conversationSource");
        providerBindingIds = Set.copyOf(Objects.requireNonNull(
                providerBindingIds, "providerBindingIds"));
        if (providerBindingIds.size() > 200) {
            throw new IllegalArgumentException("providerBindingIds must not exceed 200 values");
        }
        codingTarget = Objects.requireNonNull(codingTarget, "codingTarget");
        if (expectedWorkItemVersion < 0) {
            throw new IllegalArgumentException("expectedWorkItemVersion must not be negative");
        }
    }

    /** Preserves the M3 non-Coding delegation contract. */
    public CreateAgentTaskCommand(
            TaskBrief brief,
            AgentProfileId executorAgentProfileId,
            Optional<TaskConversationSource> conversationSource,
            Set<ProviderBindingId> providerBindingIds,
            long expectedWorkItemVersion) {
        this(
                brief,
                executorAgentProfileId,
                Optional.empty(),
                conversationSource,
                providerBindingIds,
                Optional.empty(),
                expectedWorkItemVersion);
    }

    /** Preserves the M4 Coding delegation contract while selecting the current configuration. */
    public CreateAgentTaskCommand(
            TaskBrief brief,
            AgentProfileId executorAgentProfileId,
            Optional<TaskConversationSource> conversationSource,
            Set<ProviderBindingId> providerBindingIds,
            Optional<CreateCodingTargetCommand> codingTarget,
            long expectedWorkItemVersion) {
        this(
                brief,
                executorAgentProfileId,
                Optional.empty(),
                conversationSource,
                providerBindingIds,
                codingTarget,
                expectedWorkItemVersion);
    }

    public TaskAgentSelectionRequest agentSelection() {
        return new TaskAgentSelectionRequest(
                executorAgentProfileId, agentConfigurationRevision);
    }
}
