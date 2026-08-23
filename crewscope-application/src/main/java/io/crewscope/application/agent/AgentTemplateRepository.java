package io.crewscope.application.agent;

import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import java.util.List;
import java.util.Optional;

/** Persistence Port for append-only Agent template definitions and mutable catalog lifecycle. */
public interface AgentTemplateRepository {

    /**
     * Appends one exact definition. Implementations must reject duplicate coordinates, version
     * gaps and a predecessor that differs from the currently committed latest version.
     */
    AgentTemplateDefinition append(AgentTemplateDefinition definition);

    /** Updates only lifecycle state with an optimistic lifecycle-version predicate. */
    AgentTemplateDefinition updateLifecycle(AgentTemplateDefinition definition);

    Optional<AgentTemplateDefinition> findByVersion(
            AgentTemplatePublisherScope publisherScope, AgentTemplateVersion templateVersion);

    Optional<AgentTemplateDefinition> findLatest(
            AgentTemplatePublisherScope publisherScope, AgentTemplateKey templateKey);

    /** Returns a stable template-key/version window inside one publisher boundary. */
    List<AgentTemplateDefinition> findPage(
            AgentTemplatePublisherScope publisherScope, int offset, int limit);
}
