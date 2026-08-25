package io.crewscope.application.projection;

import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Request for a new Job/Generation derived from one failed or cancelled attempt. */
public record RetryProjectionRebuildCommand(
        ProjectionAdministrationCommandId commandId,
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionRebuildJobId retryOfJobId,
        long expectedRetryOfJobVersion,
        ProjectionDefinitionVersion expectedDefinitionVersion,
        long expectedPointerVersion,
        Principal actor,
        ProjectionStrongConfirmation confirmation) {

    public RetryProjectionRebuildCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        retryOfJobId = Objects.requireNonNull(retryOfJobId, "retryOfJobId");
        expectedDefinitionVersion = Objects.requireNonNull(
                expectedDefinitionVersion, "expectedDefinitionVersion");
        actor = Objects.requireNonNull(actor, "actor");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        if (expectedRetryOfJobVersion < 0 || expectedPointerVersion < 0) {
            throw new IllegalArgumentException("Retry command versions must not be negative");
        }
        confirmation.require(
                ProjectionAdministrationAction.RETRY_REBUILD, projectionName, Optional.empty());
    }
}
