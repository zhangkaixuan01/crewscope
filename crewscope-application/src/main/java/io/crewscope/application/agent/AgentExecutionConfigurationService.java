package io.crewscope.application.agent;

import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelPreflightRejectionCode;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Loads and resolves the exact Profile, Template and configuration graph for an execution. */
public final class AgentExecutionConfigurationService {

    private final AgentProfileRepository profiles;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final AgentExecutionConfigurationResolver resolver;

    public AgentExecutionConfigurationService(
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            AgentExecutionConfigurationResolver resolver) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public ResolvedAgentExecutionConfiguration resolve(
            ResolveAgentExecutionConfigurationRequest request) {
        ResolveAgentExecutionConfigurationRequest required = Objects.requireNonNull(
                request, "request");
        AgentProfile profile = profiles.findById(
                        required.organizationId(), required.agentProfileId())
                .orElseThrow(() -> rejected(AgentModelPreflightRejectionCode.AGENT_UNAVAILABLE));
        AgentConfigurationVersion configuration = required.configurationRevision()
                .flatMap(revision -> configurations.findByRevision(
                        required.organizationId(), required.agentProfileId(), revision))
                .or(() -> required.configurationRevision().isEmpty()
                        ? configurations.findCurrent(
                                required.organizationId(), required.agentProfileId())
                        : java.util.Optional.empty())
                .orElseThrow(() -> rejected(
                        AgentModelPreflightRejectionCode.MODEL_BINDING_MISSING));
        AgentTemplateDefinition template = requireExactTemplate(profile, configuration);
        return resolver.resolve(
                profile,
                template,
                configuration,
                required.scopeFacts(),
                required.policyConstraints(),
                required.authorization(),
                required.resolvedAt());
    }

    private AgentTemplateDefinition requireExactTemplate(
            AgentProfile profile, AgentConfigurationVersion configuration) {
        List<AgentTemplateDefinition> matches = new ArrayList<>();
        profile.scope().teamId().ifPresent(teamId -> templates.findByVersion(
                        AgentTemplatePublisherScope.team(
                                profile.scope().organizationId(), teamId),
                        configuration.templateVersion())
                .filter(value -> value.contentHash().equals(configuration.templateContentHash()))
                .ifPresent(matches::add));
        templates.findByVersion(
                        AgentTemplatePublisherScope.organization(
                                profile.scope().organizationId()),
                        configuration.templateVersion())
                .filter(value -> value.contentHash().equals(configuration.templateContentHash()))
                .ifPresent(matches::add);
        if (matches.size() != 1) {
            throw rejected(AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
        return matches.get(0);
    }

    private static AgentModelPreflightException rejected(
            AgentModelPreflightRejectionCode reason) {
        return new AgentModelPreflightException(reason);
    }
}
