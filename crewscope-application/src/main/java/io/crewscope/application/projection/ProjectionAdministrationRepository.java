package io.crewscope.application.projection;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionLifecycleEvent;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildStart;
import io.crewscope.domain.projection.ProjectionSwitchPlan;
import io.crewscope.domain.projection.ProjectionTerminationPlan;
import io.crewscope.domain.projection.ProjectionValidationPlan;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Optional;

/**
 * Persistence Port for projection Registry administration.
 *
 * <p>Every save method atomically applies its domain plan, stores the command receipt, appends the
 * sanitized DomainEvent and projects the matching PROJECTION Audit record. Optimistic versions and
 * database uniqueness are rechecked at write time.
 */
public interface ProjectionAdministrationRepository {

    Optional<ProjectionCommandReceipt> findReceipt(
            OrganizationId organizationId, ProjectionAdministrationCommandId commandId);

    /** Locks the Pointer and mutable Registry rows needed for start, validation or termination. */
    ProjectionRegistrySnapshot loadForUpdate(
            OrganizationId organizationId, ProjectionName projectionName);

    /**
     * Uses the ADR-020 order: Pointer, target Generation, then old ACTIVE Generation and Job.
     * The caller calculates the fresh target snapshot while these locks remain in its transaction.
     */
    ProjectionRegistrySnapshot loadForSwitch(
            OrganizationId organizationId,
            ProjectionName projectionName,
            ProjectionGeneration targetGeneration);

    ProjectionCommandReceipt createRebuild(
            ProjectionRebuildStart start,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt);

    ProjectionCommandReceipt saveValidation(
            ProjectionValidationPlan plan,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt);

    ProjectionCommandReceipt switchGeneration(
            ProjectionSwitchPlan plan,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt);

    ProjectionCommandReceipt terminateRebuild(
            ProjectionTerminationPlan plan,
            ProjectionLifecycleEvent event,
            ProjectionCommandReceipt receipt);
}
