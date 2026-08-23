package io.crewscope.domain.agent;

import io.crewscope.domain.policy.PolicyPackReference;
import java.util.Objects;

/** Exact Team or Organization default revision used by one resolved execution. */
public record ResolvedAgentModelDefault(
        AgentModelBindingSource source,
        AgentModelDefaultScope scope,
        AgentModelDefaultRevision revision,
        AgentConfigurationHash contentHash,
        PolicyPackReference policyPack) {

    public ResolvedAgentModelDefault {
        source = Objects.requireNonNull(source, "source");
        if (source == AgentModelBindingSource.DIRECT) {
            throw new AgentModelPreflightException(
                    AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
        scope = Objects.requireNonNull(scope, "scope");
        if ((source == AgentModelBindingSource.TEAM_DEFAULT) != scope.teamId().isPresent()) {
            throw new AgentModelPreflightException(
                    AgentModelPreflightRejectionCode.COORDINATE_MISMATCH);
        }
        revision = Objects.requireNonNull(revision, "revision");
        contentHash = Objects.requireNonNull(contentHash, "contentHash");
        policyPack = Objects.requireNonNull(policyPack, "policyPack");
    }

    public static ResolvedAgentModelDefault capture(
            AgentModelBindingSource source, AgentModelDefault modelDefault) {
        AgentModelDefault required = Objects.requireNonNull(modelDefault, "modelDefault");
        return new ResolvedAgentModelDefault(
                source,
                required.scope(),
                required.revision(),
                required.contentHash(),
                required.policyPack());
    }
}
