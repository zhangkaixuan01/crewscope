package io.crewscope.domain.workspace.event;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.shared.DomainEvent;
import java.util.Objects;

/** Public-safe append-only Agent configuration fact without prompt, credential or endpoint data. */
public record AgentConfigurationChanged(
        String agentProfileId,
        long revision,
        Long previousRevision,
        String templateKey,
        long templateVersion,
        String templateContentHash,
        String configurationHash) implements DomainEvent {

    public AgentConfigurationChanged {
        Objects.requireNonNull(agentProfileId, "agentProfileId");
        Objects.requireNonNull(templateKey, "templateKey");
        Objects.requireNonNull(templateContentHash, "templateContentHash");
        Objects.requireNonNull(configurationHash, "configurationHash");
    }

    public static AgentConfigurationChanged from(AgentConfigurationVersion configuration) {
        AgentConfigurationVersion value = Objects.requireNonNull(configuration, "configuration");
        return new AgentConfigurationChanged(
                value.agentProfileId().toString(),
                value.revision().value(),
                value.previousRevision().map(revision -> revision.value()).orElse(null),
                value.templateVersion().key().toString(),
                value.templateVersion().version(),
                value.templateContentHash().toString(),
                value.configurationHash().toString());
    }
}
