package io.crewscope.application.projection;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Request to compare canonical source and shadow snapshots under exact optimistic versions. */
public record ValidateProjectionGenerationCommand(
        ProjectionAdministrationCommandId commandId,
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionDefinitionVersion expectedDefinitionVersion,
        ProjectionGeneration generation,
        ProjectionRebuildJobId rebuildJobId,
        long expectedGenerationVersion,
        long expectedJobVersion,
        Principal actor,
        ProjectionStrongConfirmation confirmation) {

    public ValidateProjectionGenerationCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        expectedDefinitionVersion = Objects.requireNonNull(
                expectedDefinitionVersion, "expectedDefinitionVersion");
        generation = Objects.requireNonNull(generation, "generation");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        actor = Objects.requireNonNull(actor, "actor");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        if (expectedGenerationVersion < 0 || expectedJobVersion < 0) {
            throw new IllegalArgumentException("Validation command versions must not be negative");
        }
        confirmation.require(
                ProjectionAdministrationAction.VALIDATE_GENERATION,
                projectionName,
                Optional.of(generation));
    }
}
