package io.crewscope.domain.conversation;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** Stable opaque reference used to locate external Agent state without storing that state here. */
public record AgentRuntimeStateReference(String value) {

    private static final String PREFIX = "crewscope:agent-state:v1:";

    public AgentRuntimeStateReference {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(
                    "agentRuntimeSession.stateReference", "must not be blank");
        }
        value = value.strip();
        String identifier = value.substring(Math.min(PREFIX.length(), value.length()));
        if (!value.startsWith(PREFIX) || !isCanonicalSessionId(identifier)) {
            throw new DomainValidationException(
                    "agentRuntimeSession.stateReference",
                    "must use the canonical CrewScope Agent state reference format");
        }
    }

    public static AgentRuntimeStateReference forSession(AgentRuntimeSessionId sessionId) {
        return new AgentRuntimeStateReference(
                PREFIX + Objects.requireNonNull(sessionId, "sessionId"));
    }

    public boolean belongsTo(AgentRuntimeSessionId sessionId) {
        return equals(forSession(sessionId));
    }

    private static boolean isCanonicalSessionId(String value) {
        try {
            return AgentRuntimeSessionId.from(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
