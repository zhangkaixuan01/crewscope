package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;
import java.util.Optional;

/** Authorized management projection joining stable identity with the current configuration. */
public record ManagedAgentView(
        Principal principal,
        AgentProfile profile,
        Optional<AgentConfigurationVersion> currentConfiguration) {

    public ManagedAgentView {
        principal = Objects.requireNonNull(principal, "principal");
        profile = Objects.requireNonNull(profile, "profile");
        currentConfiguration = Objects.requireNonNull(
                currentConfiguration, "currentConfiguration");
        if (!principal.id().equals(profile.agentPrincipalId())) {
            throw new IllegalArgumentException("Agent management projection identity is inconsistent");
        }
        if (currentConfiguration.isPresent()
                && !currentConfiguration.orElseThrow().agentProfileId().equals(profile.id())) {
            throw new IllegalArgumentException(
                    "Current configuration must belong to the projected AgentProfile");
        }
    }
}
