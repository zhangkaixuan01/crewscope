package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/** Versioned, unambiguous AgentScope {@code (userId, sessionId)} state isolation key. */
public record AgentScopeSessionKey(String userId, String sessionId) {

    private static final String USER_PREFIX = "crewscope:v1:user:";
    private static final String SESSION_PREFIX = "crewscope:v1:session:";

    public AgentScopeSessionKey {
        userId = requireValue(userId, USER_PREFIX, "agentRuntimeSession.agentScopeUserId");
        sessionId = requireValue(
                sessionId, SESSION_PREFIX, "agentRuntimeSession.agentScopeSessionId");
    }

    /** Encodes only trusted durable identities; request-provided thread or Agent IDs are excluded. */
    public static AgentScopeSessionKey forPersonalConversation(
            OrganizationId organizationId,
            TeamMemberId ownerMemberId,
            PrincipalId personalAgentPrincipalId,
            ConversationId conversationId,
            AgentRuntimeSessionId runtimeSessionId) {
        String userId = USER_PREFIX
                + Objects.requireNonNull(organizationId, "organizationId")
                + ":"
                + Objects.requireNonNull(ownerMemberId, "ownerMemberId")
                + ":"
                + Objects.requireNonNull(personalAgentPrincipalId, "personalAgentPrincipalId");
        String sessionId = SESSION_PREFIX
                + Objects.requireNonNull(conversationId, "conversationId")
                + ":"
                + Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        return new AgentScopeSessionKey(userId, sessionId);
    }

    /** Builds a Task-scoped AgentScope key without accepting caller-provided runtime coordinates. */
    public static AgentScopeSessionKey forTaskExecution(
            OrganizationId organizationId,
            PrincipalId agentPrincipalId,
            TaskExecutionId executionId,
            AgentRuntimeSessionId runtimeSessionId) {
        String userId = USER_PREFIX
                + Objects.requireNonNull(organizationId, "organizationId")
                + ":"
                + Objects.requireNonNull(agentPrincipalId, "agentPrincipalId");
        String sessionId = SESSION_PREFIX
                + Objects.requireNonNull(executionId, "executionId")
                + ":"
                + Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        return new AgentScopeSessionKey(userId, sessionId);
    }

    private static String requireValue(String value, String prefix, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field, "must not be blank");
        }
        String normalized = value.strip();
        if (!normalized.startsWith(prefix) || normalized.length() == prefix.length()) {
            throw new DomainValidationException(
                    field, "must use the versioned CrewScope AgentScope key format");
        }
        return normalized;
    }
}
