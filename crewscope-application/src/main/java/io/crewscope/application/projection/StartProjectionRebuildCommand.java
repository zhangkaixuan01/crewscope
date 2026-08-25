package io.crewscope.application.projection;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Strong-versioned request to register a new shadow Generation. */
public record StartProjectionRebuildCommand(
        ProjectionAdministrationCommandId commandId,
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionDefinitionVersion expectedDefinitionVersion,
        long expectedPointerVersion,
        Principal actor,
        ProjectionStrongConfirmation confirmation) {

    public StartProjectionRebuildCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        expectedDefinitionVersion = Objects.requireNonNull(
                expectedDefinitionVersion, "expectedDefinitionVersion");
        actor = Objects.requireNonNull(actor, "actor");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        if (expectedPointerVersion < 0) {
            throw new IllegalArgumentException("expectedPointerVersion must not be negative");
        }
        confirmation.require(
                ProjectionAdministrationAction.START_REBUILD, projectionName, Optional.empty());
    }
}
