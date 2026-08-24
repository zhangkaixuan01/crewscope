package io.crewscope.domain.conversation;

import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;
import java.util.Optional;

/** Immutable M5 Agent identity and optional configuration fixed to one runtime Session. */
public record AgentRuntimeConfigurationPin(
        AgentOwnershipType ownershipType,
        AgentRuntimeRole runtimeRole,
        AgentTemplateVersion templateVersion,
        Optional<AgentConfigurationRevision> configurationRevision,
        Optional<AgentConfigurationHash> configurationHash) {

    public AgentRuntimeConfigurationPin {
        ownershipType = Objects.requireNonNull(ownershipType, "ownershipType");
        runtimeRole = Objects.requireNonNull(runtimeRole, "runtimeRole");
        templateVersion = Objects.requireNonNull(templateVersion, "templateVersion");
        configurationRevision = Objects.requireNonNull(
                configurationRevision, "configurationRevision");
        configurationHash = Objects.requireNonNull(configurationHash, "configurationHash");
        if (configurationRevision.isPresent() != configurationHash.isPresent()) {
            throw new DomainValidationException(
                    "agentRuntimeSession.configuration",
                    "revision and hash must be present or absent together");
        }
    }

    public static AgentRuntimeConfigurationPin from(
            AgentProfile profile, Optional<AgentConfigurationVersion> configuration) {
        AgentProfile requiredProfile = Objects.requireNonNull(profile, "profile");
        Optional<AgentConfigurationVersion> requiredConfiguration = Objects.requireNonNull(
                configuration, "configuration");
        requiredConfiguration.ifPresent(value -> {
            if (!value.organizationId().equals(requiredProfile.scope().organizationId())
                    || !value.agentProfileId().equals(requiredProfile.id())
                    || !value.ownership().equals(requiredProfile.ownership())
                    || !value.templateVersion().equals(requiredProfile.templateVersion())) {
                throw new DomainValidationException(
                        "agentRuntimeSession.configuration",
                        "must belong to the pinned AgentProfile and template");
            }
        });
        return new AgentRuntimeConfigurationPin(
                requiredProfile.ownership().type(),
                requiredProfile.runtimeRole(),
                requiredProfile.templateVersion(),
                requiredConfiguration.map(AgentConfigurationVersion::revision),
                requiredConfiguration.map(AgentConfigurationVersion::configurationHash));
    }

    public AgentRuntimeConfigurationPin refresh(
            AgentProfile profile, AgentConfigurationVersion configuration) {
        AgentRuntimeConfigurationPin next = from(
                profile, Optional.of(Objects.requireNonNull(configuration, "configuration")));
        if (ownershipType != next.ownershipType
                || runtimeRole != next.runtimeRole
                || !templateVersion.equals(next.templateVersion)) {
            throw new DomainValidationException(
                    "agentRuntimeSession.configuration",
                    "must preserve the pinned Agent identity and template");
        }
        if (configurationRevision
                .filter(current -> current.value() >= next.configurationRevision.orElseThrow().value())
                .isPresent()) {
            throw new DomainValidationException(
                    "agentRuntimeSession.configurationRevision",
                    "must advance to a newer configuration revision");
        }
        return next;
    }
}
