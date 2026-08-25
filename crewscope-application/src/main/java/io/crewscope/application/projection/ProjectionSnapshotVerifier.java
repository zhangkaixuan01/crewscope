package io.crewscope.application.projection;

import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionGenerationState;
import io.crewscope.domain.projection.ProjectionSnapshot;

/** Infrastructure Port for canonical source/target comparison and switch-time freshness checks. */
public interface ProjectionSnapshotVerifier {

    ProjectionVerificationSnapshots verify(
            ProjectionDefinition definition, ProjectionGenerationState target);

    ProjectionSnapshot current(
            ProjectionDefinition definition, ProjectionGenerationState target);
}
