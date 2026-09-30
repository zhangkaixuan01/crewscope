package io.crewscope.agentscope.model;

import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import java.util.Objects;
import java.util.UUID;

/**
 * Production {@link TaskResolvedModelSource}: delegates to the same trusted, connection-scoped
 * factory the conversation runtime uses, so a Task executes on the exact Model coordinates its
 * PolicySnapshot resolved at delegation time. The Worker itself is the requesting principal
 * context, so the credential audit trail records the Agent Principal that owns the configuration
 * and a fresh correlation id per build.
 */
public final class PolicySnapshotTaskModelSource implements TaskResolvedModelSource {

    private final ResolvedAgentScopeModelFactory models;

    public PolicySnapshotTaskModelSource(ResolvedAgentScopeModelFactory models) {
        this.models = Objects.requireNonNull(models, "models");
    }

    @Override
    public ResolvedAgentScopeModels build(ResolvedAgentExecutionConfiguration resolved) {
        ResolvedAgentExecutionConfiguration required =
                Objects.requireNonNull(resolved, "resolved");
        return models.build(
                required.ownership().organizationId(),
                required,
                SafeModelGenerateOptions.defaults(),
                required.agentPrincipalId(),
                UUID.randomUUID());
    }
}
