package io.crewscope.agentscope.task;

import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.application.execution.TaskExecutionRuntimeFacts;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/** Stable in-process key for one Task execution and its AgentScope session slot. */
record AgentScopeExecutionKey(TaskExecutionId executionId, String userId, String sessionId) {

    AgentScopeExecutionKey {
        executionId = Objects.requireNonNull(executionId, "executionId");
        userId = requireText(userId, "userId", 500);
        sessionId = requireText(sessionId, "sessionId", 500);
    }

    static AgentScopeExecutionKey from(TaskExecutionRuntimeFacts facts) {
        TaskExecutionRuntimeFacts required = Objects.requireNonNull(facts, "facts");
        AgentScopeSessionKey key = required.runtimeSession().agentScopeKey();
        return new AgentScopeExecutionKey(required.execution().id(), key.userId(), key.sessionId());
    }

    private static String requireText(String value, String field, int maximumLength) {
        String required = Objects.requireNonNull(value, field).strip();
        if (required.isEmpty() || required.length() > maximumLength
                || required.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains invalid text");
        }
        return required;
    }
}
