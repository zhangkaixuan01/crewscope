package io.crewscope.domain.task.event;

import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.task.PolicySnapshot;
import io.crewscope.domain.task.SafetyEnforcementOverlay;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Auditable creation fact for the complete Task and first scheduler-visible execution graph. */
public record TaskDelegatedToAgent(
        UUID taskId,
        UUID taskExecutionId,
        UUID workItemId,
        long workItemVersion,
        String objective,
        List<String> acceptanceCriteria,
        String briefHash,
        Optional<UUID> sourceConversationId,
        Optional<UUID> sourceMessageId,
        Optional<Long> sourceMessageSequence,
        UUID executorPrincipalId,
        UUID executorAssignmentId,
        long executorAssignmentVersion,
        UUID agentProfileId,
        long agentProfileVersion,
        UUID policySnapshotId,
        String policySnapshotHash,
        UUID safetyOverlayId,
        long safetyOverlayVersion,
        List<UUID> providerBindingIds,
        String taskStatus,
        String executionStatus,
        Optional<String> agentExecutionScope,
        Optional<Long> agentConfigurationRevision,
        Optional<String> agentConfigurationHash,
        Optional<String> agentModelBindingSource) implements DomainEvent {

    public TaskDelegatedToAgent {
        taskId = Objects.requireNonNull(taskId, "taskId");
        taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        workItemId = Objects.requireNonNull(workItemId, "workItemId");
        if (workItemVersion < 0 || executorAssignmentVersion < 0 || agentProfileVersion < 0
                || safetyOverlayVersion < 1) {
            throw new IllegalArgumentException("delegation versions must be non-negative");
        }
        objective = Objects.requireNonNull(objective, "objective");
        acceptanceCriteria = List.copyOf(Objects.requireNonNull(
                acceptanceCriteria, "acceptanceCriteria"));
        briefHash = Objects.requireNonNull(briefHash, "briefHash");
        sourceConversationId = Objects.requireNonNull(
                sourceConversationId, "sourceConversationId");
        sourceMessageId = Objects.requireNonNull(sourceMessageId, "sourceMessageId");
        sourceMessageSequence = Objects.requireNonNull(
                sourceMessageSequence, "sourceMessageSequence");
        if (sourceConversationId.isPresent() != sourceMessageId.isPresent()
                || sourceConversationId.isPresent() != sourceMessageSequence.isPresent()) {
            throw new IllegalArgumentException("Conversation source fields must be present together");
        }
        executorPrincipalId = Objects.requireNonNull(executorPrincipalId, "executorPrincipalId");
        executorAssignmentId = Objects.requireNonNull(executorAssignmentId, "executorAssignmentId");
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        policySnapshotId = Objects.requireNonNull(policySnapshotId, "policySnapshotId");
        policySnapshotHash = Objects.requireNonNull(policySnapshotHash, "policySnapshotHash");
        safetyOverlayId = Objects.requireNonNull(safetyOverlayId, "safetyOverlayId");
        providerBindingIds = List.copyOf(Objects.requireNonNull(
                providerBindingIds, "providerBindingIds"));
        taskStatus = Objects.requireNonNull(taskStatus, "taskStatus");
        executionStatus = Objects.requireNonNull(executionStatus, "executionStatus");
        agentExecutionScope = Objects.requireNonNull(agentExecutionScope, "agentExecutionScope");
        agentConfigurationRevision = Objects.requireNonNull(
                agentConfigurationRevision, "agentConfigurationRevision");
        agentConfigurationHash = Objects.requireNonNull(
                agentConfigurationHash, "agentConfigurationHash");
        agentModelBindingSource = Objects.requireNonNull(
                agentModelBindingSource, "agentModelBindingSource");
        long configuredFields = java.util.stream.Stream.of(
                        agentExecutionScope,
                        agentConfigurationRevision,
                        agentConfigurationHash,
                        agentModelBindingSource)
                .filter(Optional::isPresent)
                .count();
        if (configuredFields != 0 && configuredFields != 4) {
            throw new IllegalArgumentException(
                    "Agent execution configuration audit fields must be present together");
        }
    }

    public static TaskDelegatedToAgent from(
            Task task,
            TaskExecution execution,
            PolicySnapshot policy,
            SafetyEnforcementOverlay overlay,
            AgentProfile profile) {
        Task requiredTask = Objects.requireNonNull(task, "task");
        PolicySnapshot requiredPolicy = Objects.requireNonNull(policy, "policy");
        return new TaskDelegatedToAgent(
                requiredTask.id().value(),
                Objects.requireNonNull(execution, "execution").id().value(),
                requiredTask.workItemId().value(),
                requiredTask.source().workItemVersion(),
                requiredTask.brief().objective(),
                requiredTask.brief().acceptanceCriteria(),
                requiredTask.brief().contentHash().value(),
                requiredTask.source().conversationId().map(value -> value.value()),
                requiredTask.source().inputReference().map(value -> value.referenceId()),
                requiredTask.source().inputReference().map(value -> value.referenceVersion()),
                requiredPolicy.executionPrincipal().principalId().value(),
                requiredPolicy.executionPrincipal().assignmentId().value(),
                requiredPolicy.executionPrincipal().assignmentVersion(),
                Objects.requireNonNull(profile, "profile").id().value(),
                profile.version(),
                requiredPolicy.id().value(),
                requiredPolicy.snapshotHash().value(),
                Objects.requireNonNull(overlay, "overlay").id().value(),
                overlay.version(),
                requiredPolicy.providerBindingIds().stream()
                        .map(value -> value.value())
                        .sorted()
                        .toList(),
                requiredTask.status().name(),
                execution.status().name(),
                requiredPolicy.agentExecutionConfiguration()
                        .map(value -> value.executionScope().name()),
                requiredPolicy.agentExecutionConfiguration()
                        .map(value -> value.configurationRevision().value()),
                requiredPolicy.agentExecutionConfiguration()
                        .map(value -> value.configurationHash().toString()),
                requiredPolicy.agentExecutionConfiguration()
                        .map(value -> value.bindingSource().name()));
    }
}
