package io.crewscope.agentscope.coding;

import io.crewscope.domain.coding.CodingCheckpointWorkState;
import java.util.Objects;

/** Sensitive AgentScope hot-state payload returned for M4-I12 durable checkpoint publication. */
public record CodingSpecialistStateSnapshot(
        String stableAgentId,
        String userId,
        String sessionId,
        String agentStateJson,
        CodingCheckpointWorkState workState) {

    public CodingSpecialistStateSnapshot {
        stableAgentId = requireText(stableAgentId, "stableAgentId", 500);
        userId = requireText(userId, "userId", 500);
        sessionId = requireText(sessionId, "sessionId", 500);
        agentStateJson = requireText(agentStateJson, "agentStateJson", 5_000_000);
        workState = Objects.requireNonNull(workState, "workState");
    }

    private static String requireText(String value, String field, int maximumLength) {
        String required = Objects.requireNonNull(value, field);
        if (required.isBlank() || required.length() > maximumLength) {
            throw new IllegalArgumentException(field + " contains invalid text");
        }
        return required;
    }

    @Override
    public String toString() {
        return "CodingSpecialistStateSnapshot[stableAgentId=" + stableAgentId
                + ", userId=" + userId
                + ", sessionId=" + sessionId
                + ", workStateHash=" + workState.contentHash()
                + ", agentStateJson=[REDACTED]]";
    }
}
