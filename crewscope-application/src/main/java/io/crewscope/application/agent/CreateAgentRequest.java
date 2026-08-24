package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import java.util.Objects;

/** Public Agent creation intent; identity type, Workspace and owner coordinates stay server-owned. */
public record CreateAgentRequest(
        AgentOwnershipType ownershipType,
        AgentTemplatePublisherScope templatePublisherScope,
        AgentTemplateVersion templateVersion,
        String displayName) {

    public CreateAgentRequest {
        ownershipType = Objects.requireNonNull(ownershipType, "ownershipType");
        templatePublisherScope = Objects.requireNonNull(
                templatePublisherScope, "templatePublisherScope");
        templateVersion = Objects.requireNonNull(templateVersion, "templateVersion");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        displayName = displayName.strip();
    }
}
