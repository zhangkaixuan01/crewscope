package io.crewscope.application.execution;

import io.crewscope.domain.shared.id.AggregateId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.task.TaskAgentSessionPurpose;
import java.util.Objects;
import java.util.UUID;

/** Stable AgentScope and Agent version coordinates carried by one Task state snapshot. */
public record TaskAgentStateIdentity(
        UUID taskExecutionId,
        UUID agentRunId,
        String agentName,
        String agentId,
        String agentVersion,
        String userId,
        String sessionId) {

    public TaskAgentStateIdentity {
        taskExecutionId = requireId(taskExecutionId, "taskExecutionId");
        agentRunId = requireId(agentRunId, "agentRunId");
        agentName = requireText(agentName, "agentName", 100);
        agentId = requireText(agentId, "agentId", 200);
        agentVersion = requireText(agentVersion, "agentVersion", 100);
        userId = requireText(userId, "userId", 500);
        sessionId = requireText(sessionId, "sessionId", 500);
    }

    /** Stable Harness namespace shared by Worker creation and cross-Worker snapshot validation. */
    public static String stableAgentId(AgentProfileId profileId, long profileVersion) {
        return stableAgentId(profileId, profileVersion, TaskAgentSessionPurpose.TASK);
    }

    /** Stable namespace varies by runtime role so Task and Coding state can never alias. */
    public static String stableAgentId(
            AgentProfileId profileId,
            long profileVersion,
            TaskAgentSessionPurpose purpose) {
        AgentProfileId required = Objects.requireNonNull(profileId, "profileId");
        TaskAgentSessionPurpose requiredPurpose = Objects.requireNonNull(purpose, "purpose");
        if (profileVersion < 0) {
            throw new IllegalArgumentException("profileVersion must not be negative");
        }
        String role = requiredPurpose == TaskAgentSessionPurpose.SPECIALIST ? "coding" : "task";
        return "crewscope-" + role + "-" + required + "-v" + profileVersion;
    }

    private static UUID requireId(UUID value, String field) {
        UUID required = Objects.requireNonNull(value, field);
        if (AggregateId.NIL_UUID.equals(required)) {
            throw new IllegalArgumentException(field + " must not use the nil UUID");
        }
        return required;
    }

    private static String requireText(String value, String field, int maximumLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty()
                || required.length() > maximumLength
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains invalid text");
        }
        return required;
    }
}
