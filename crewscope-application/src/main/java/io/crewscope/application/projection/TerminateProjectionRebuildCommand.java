package io.crewscope.application.projection;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Explicit cancellation or bounded-code failure of one shadow rebuild attempt. */
public record TerminateProjectionRebuildCommand(
        ProjectionAdministrationCommandId commandId,
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionGeneration generation,
        ProjectionRebuildJobId rebuildJobId,
        long expectedGenerationVersion,
        long expectedJobVersion,
        ProjectionAdministrationAction action,
        Optional<ProjectionFailureCode> failureCode,
        TeamAccessContext access,
        ProjectionStrongConfirmation confirmation) {

    public TerminateProjectionRebuildCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        generation = Objects.requireNonNull(generation, "generation");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        action = Objects.requireNonNull(action, "action");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        access = Objects.requireNonNull(access, "access");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        if (expectedGenerationVersion < 0 || expectedJobVersion < 0) {
            throw new IllegalArgumentException("Termination command versions must not be negative");
        }
        if (action != ProjectionAdministrationAction.CANCEL_REBUILD
                && action != ProjectionAdministrationAction.FAIL_REBUILD) {
            throw new IllegalArgumentException("Termination action must be CANCEL_REBUILD or FAIL_REBUILD");
        }
        if ((action == ProjectionAdministrationAction.FAIL_REBUILD) != failureCode.isPresent()) {
            throw new IllegalArgumentException("Only FAIL_REBUILD requires a bounded failure code");
        }
        confirmation.require(action, projectionName, Optional.of(generation));
    }
}
