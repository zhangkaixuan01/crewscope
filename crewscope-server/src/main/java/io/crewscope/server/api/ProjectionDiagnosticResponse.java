package io.crewscope.server.api;

import io.crewscope.application.operations.ProjectionHealthDiagnostic;
import io.crewscope.application.projection.ProjectionAdministrationAction;
import io.crewscope.application.projection.ProjectionStrongConfirmation;
import io.crewscope.domain.projection.ProjectionFailureCode;
import io.crewscope.domain.projection.ProjectionGeneration;
import java.util.Optional;

/** Safe projection coordinates plus the strong confirmation phrases of each command. */
public record ProjectionDiagnosticResponse(
        String projectionName,
        long definitionVersion,
        long activeGeneration,
        long pointerVersion,
        long activeGenerationVersion,
        Optional<Long> shadowGeneration,
        Optional<String> shadowStatus,
        Optional<Long> shadowGenerationVersion,
        Optional<String> rebuildJobId,
        Optional<Long> rebuildJobVersion,
        long lagSeconds,
        long gapCount,
        long deadLetterCount,
        Optional<String> latestFailureCode,
        String startConfirmation,
        Optional<String> validateConfirmation,
        Optional<String> switchConfirmation,
        Optional<String> cancelConfirmation,
        Optional<String> failConfirmation) {

    static ProjectionDiagnosticResponse from(ProjectionHealthDiagnostic value) {
        Optional<ProjectionGeneration> shadow = value.shadowGeneration();
        return new ProjectionDiagnosticResponse(
                value.projectionName().value(),
                value.definitionVersion().value(),
                value.activeGeneration().value(),
                value.pointerVersion(),
                value.activeGenerationVersion(),
                shadow.map(ProjectionGeneration::value),
                value.shadowStatus().map(Enum::name),
                value.shadowGenerationVersion().isPresent()
                        ? Optional.of(value.shadowGenerationVersion().getAsLong())
                        : Optional.empty(),
                value.rebuildJobId().map(valueId -> valueId.value().toString()),
                value.rebuildJobVersion().isPresent()
                        ? Optional.of(value.rebuildJobVersion().getAsLong())
                        : Optional.empty(),
                value.lagSeconds(),
                value.gapCount(),
                value.deadLetterCount(),
                value.latestFailureCode().map(ProjectionFailureCode::value),
                ProjectionStrongConfirmation.expectedPhrase(
                        ProjectionAdministrationAction.START_REBUILD,
                        value.projectionName(),
                        Optional.empty()),
                phrase(value, ProjectionAdministrationAction.VALIDATE_GENERATION),
                phrase(value, ProjectionAdministrationAction.SWITCH_GENERATION),
                phrase(value, ProjectionAdministrationAction.CANCEL_REBUILD),
                phrase(value, ProjectionAdministrationAction.FAIL_REBUILD));
    }

    private static Optional<String> phrase(
            ProjectionHealthDiagnostic value, ProjectionAdministrationAction action) {
        return value.shadowGeneration().map(generation ->
                ProjectionStrongConfirmation.expectedPhrase(
                        action, value.projectionName(), Optional.of(generation)));
    }
}
