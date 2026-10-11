package io.crewscope.server.api;

import io.crewscope.application.projection.ProjectionAdministrationCommandId;
import io.crewscope.application.projection.ProjectionAdministrationResult;
import java.util.Optional;

/** Projection administration command outcome with the next expected versions. */
public record ProjectionCommandResponse(
        String commandId,
        String projectionName,
        long generation,
        String rebuildJobId,
        String generationStatus,
        String rebuildStatus,
        long generationVersion,
        long rebuildJobVersion,
        Optional<Long> pointerVersion) {

    static ProjectionCommandResponse from(
            ProjectionAdministrationCommandId commandId,
            ProjectionAdministrationResult value,
            long generationVersion,
            long rebuildJobVersion) {
        return new ProjectionCommandResponse(
                commandId.value().toString(),
                value.projectionName().value(),
                value.generation().value(),
                value.rebuildJobId().value().toString(),
                value.generationStatus().name(),
                value.rebuildStatus().name(),
                generationVersion,
                rebuildJobVersion,
                value.pointerVersion().isPresent()
                        ? Optional.of(value.pointerVersion().getAsLong())
                        : Optional.empty());
    }
}
