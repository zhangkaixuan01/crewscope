package io.crewscope.application.task;

import io.crewscope.domain.shared.id.ArtifactId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.RuntimeArtifact;
import io.crewscope.domain.task.RuntimeArtifactId;
import java.util.List;
import java.util.Optional;

/** Metadata Repository for immutable ArtifactStore objects produced by Agent runs. */
public interface RuntimeArtifactRepository {

    RuntimeArtifact create(RuntimeArtifact artifact);

    Optional<RuntimeArtifact> findById(
            OrganizationId organizationId, RuntimeArtifactId runtimeArtifactId);

    Optional<RuntimeArtifact> findByArtifactId(
            OrganizationId organizationId, ArtifactId artifactId);

    List<RuntimeArtifact> findByAgentRun(
            OrganizationId organizationId, AgentRunId agentRunId);
}
