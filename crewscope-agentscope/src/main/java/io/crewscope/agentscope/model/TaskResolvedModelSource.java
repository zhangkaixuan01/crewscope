package io.crewscope.agentscope.model;

import io.agentscope.core.model.Model;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;

/**
 * Materializes the model pair a Task pinned at delegation time. The Worker's stable
 * {@code crewscope-primary} slot resolves the deployment's configured AgentScope Model bean, which
 * page-configured credentials never publish — this source closes that gap by rebuilding the exact
 * trusted connection the Task's PolicySnapshot resolved when it was created.
 */
@FunctionalInterface
public interface TaskResolvedModelSource {

    /**
     * Returns the observation-wrapped primary (and optional fallback) models for one resolved
     * configuration. Implementations must close the credential handle inside the synchronous
     * build window, exactly as the conversation runtime does.
     */
    ResolvedAgentScopeModels build(ResolvedAgentExecutionConfiguration resolved);
}
