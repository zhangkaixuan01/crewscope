package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentModelPolicyConstraints;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfileId;
import java.util.Objects;
import java.util.Optional;

/** Trusted input for resolving one current or explicitly pinned Agent configuration. */
public record ResolveAgentExecutionConfigurationRequest(
        OrganizationId organizationId,
        AgentProfileId agentProfileId,
        Optional<AgentConfigurationRevision> configurationRevision,
        AgentExecutionScopeFacts scopeFacts,
        AgentModelPolicyConstraints policyConstraints,
        AgentExecutionAuthorizationFacts authorization,
        UtcTimestamp resolvedAt) {

    public ResolveAgentExecutionConfigurationRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        agentProfileId = Objects.requireNonNull(agentProfileId, "agentProfileId");
        configurationRevision = Objects.requireNonNull(
                configurationRevision, "configurationRevision");
        scopeFacts = Objects.requireNonNull(scopeFacts, "scopeFacts");
        policyConstraints = Objects.requireNonNull(policyConstraints, "policyConstraints");
        authorization = Objects.requireNonNull(authorization, "authorization");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }
}
