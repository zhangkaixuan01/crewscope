package io.crewscope.application.projection;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.projection.ProjectionDefinitionVersion;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.projection.ProjectionRebuildJobId;
import io.crewscope.domain.shared.id.OrganizationId;
import java.util.Objects;
import java.util.Optional;

/** Atomic Pointer switch request with versions for every row locked by the transaction. */
public record SwitchProjectionGenerationCommand(
        ProjectionAdministrationCommandId commandId,
        OrganizationId organizationId,
        ProjectionName projectionName,
        ProjectionDefinitionVersion expectedDefinitionVersion,
        ProjectionGeneration previousActiveGeneration,
        ProjectionGeneration targetGeneration,
        ProjectionRebuildJobId rebuildJobId,
        long expectedPointerVersion,
        long expectedPreviousGenerationVersion,
        long expectedTargetGenerationVersion,
        long expectedJobVersion,
        TeamAccessContext access,
        ProjectionStrongConfirmation confirmation) {

    public SwitchProjectionGenerationCommand {
        commandId = Objects.requireNonNull(commandId, "commandId");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        expectedDefinitionVersion = Objects.requireNonNull(
                expectedDefinitionVersion, "expectedDefinitionVersion");
        previousActiveGeneration = Objects.requireNonNull(
                previousActiveGeneration, "previousActiveGeneration");
        targetGeneration = Objects.requireNonNull(targetGeneration, "targetGeneration");
        rebuildJobId = Objects.requireNonNull(rebuildJobId, "rebuildJobId");
        access = Objects.requireNonNull(access, "access");
        confirmation = Objects.requireNonNull(confirmation, "confirmation");
        if (expectedPointerVersion < 0
                || expectedPreviousGenerationVersion < 0
                || expectedTargetGenerationVersion < 0
                || expectedJobVersion < 0) {
            throw new IllegalArgumentException("Switch command versions must not be negative");
        }
        if (previousActiveGeneration.equals(targetGeneration)) {
            throw new IllegalArgumentException("Switch target must differ from the active Generation");
        }
        confirmation.require(
                ProjectionAdministrationAction.SWITCH_GENERATION,
                projectionName,
                Optional.of(targetGeneration));
    }
}
