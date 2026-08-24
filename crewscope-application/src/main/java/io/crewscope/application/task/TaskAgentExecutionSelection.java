package io.crewscope.application.task;

import io.crewscope.application.agent.ResolveAgentExecutionConfigurationRequest;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.Objects;

/** Trusted Agent, responsibility, execution-scope and model facts for one Task attempt. */
public record TaskAgentExecutionSelection(
        AgentProfile profile,
        Principal executor,
        ResolveAgentExecutionConfigurationRequest resolutionRequest,
        ResolvedAgentExecutionConfiguration resolvedConfiguration) {

    public TaskAgentExecutionSelection {
        profile = Objects.requireNonNull(profile, "profile");
        executor = Objects.requireNonNull(executor, "executor");
        resolutionRequest = Objects.requireNonNull(resolutionRequest, "resolutionRequest");
        resolvedConfiguration = Objects.requireNonNull(
                resolvedConfiguration, "resolvedConfiguration");
        if (!profile.id().equals(resolvedConfiguration.agentProfileId())
                || !executor.id().equals(resolvedConfiguration.agentPrincipalId())) {
            throw new IllegalArgumentException(
                    "selected Profile, executor and resolved configuration must share an Agent");
        }
    }
}
