package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.AgentModelDefaultRevision;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import java.util.List;
import java.util.Optional;

/** Persistence Port for scoped append-only model defaults. */
public interface AgentModelDefaultRepository {

    /** Appends the next default revision for one Scope, Template and ExecutionScope key. */
    AgentModelDefault append(AgentModelDefault modelDefault);

    Optional<AgentModelDefault> findCurrent(
            AgentModelDefaultScope scope,
            AgentTemplateVersion templateVersion,
            AgentExecutionScope executionScope);

    /**
     * Returns all rows that claim to be current so the resolver can fail closed on corrupted or
     * eventually-consistent duplicate defaults. Persistent adapters should implement this query
     * directly; the default keeps existing in-memory adapters source compatible.
     */
    default List<AgentModelDefault> findCurrentCandidates(
            AgentModelDefaultScope scope,
            AgentTemplateVersion templateVersion,
            AgentExecutionScope executionScope) {
        return findCurrent(scope, templateVersion, executionScope).stream().toList();
    }

    Optional<AgentModelDefault> findByRevision(
            AgentModelDefaultScope scope,
            AgentTemplateVersion templateVersion,
            AgentExecutionScope executionScope,
            AgentModelDefaultRevision revision);
}
