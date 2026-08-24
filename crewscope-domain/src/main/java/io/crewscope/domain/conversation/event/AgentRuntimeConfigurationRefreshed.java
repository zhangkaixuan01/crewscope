package io.crewscope.domain.conversation.event;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Safe Conversation Session fact identifying the newly pinned Agent configuration. */
public record AgentRuntimeConfigurationRefreshed(
        String conversationId,
        String runtimeSessionId,
        String agentProfileId,
        long configurationRevision,
        String configurationHash,
        long version) implements DomainEvent {

    public AgentRuntimeConfigurationRefreshed {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        Objects.requireNonNull(agentProfileId, "agentProfileId");
        Objects.requireNonNull(configurationHash, "configurationHash");
    }

    public static AgentRuntimeConfigurationRefreshed from(
            AgentRuntimeSession session, AgentConfigurationVersion configuration) {
        AgentRuntimeSession value = Objects.requireNonNull(session, "session");
        AgentConfigurationVersion config = Objects.requireNonNull(configuration, "configuration");
        return new AgentRuntimeConfigurationRefreshed(
                value.conversationId().toString(),
                value.id().toString(),
                value.agentProfileId().toString(),
                config.revision().value(),
                config.configurationHash().toString(),
                value.version());
    }
}
