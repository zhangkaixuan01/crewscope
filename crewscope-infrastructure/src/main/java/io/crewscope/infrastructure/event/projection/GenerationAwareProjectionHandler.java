package io.crewscope.infrastructure.event.projection;

import io.crewscope.domain.projection.ProjectionDefinition;
import io.crewscope.domain.projection.ProjectionGenerationKey;
import io.crewscope.domain.projection.ProjectionGenerationLease;
import io.crewscope.domain.projection.ProjectionSnapshot;
import io.crewscope.domain.shared.id.OrganizationId;

/**
 * Generation-aware projection plug-in implemented by each M6 read model.
 *
 * <p>The handler writes only rows bound to the supplied lease. Expected and actual snapshots use
 * the same canonical row contract declared by {@link #definition()}.
 */
public interface GenerationAwareProjectionHandler {

    ProjectionDefinition definition();

    /** Applies or intentionally ignores one ordered event inside the runner transaction. */
    void project(ProjectionGenerationLease lease, ProjectionEvent event);

    /** Builds the canonical expected snapshot from authoritative source facts. */
    ProjectionSnapshot expectedSnapshot(OrganizationId organizationId);

    /** Builds the canonical snapshot currently stored for one exact Generation. */
    ProjectionSnapshot actualSnapshot(ProjectionGenerationKey generationKey);
}
