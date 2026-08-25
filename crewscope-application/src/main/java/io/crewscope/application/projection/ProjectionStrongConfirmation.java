package io.crewscope.application.projection;

import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import java.util.Objects;
import java.util.Optional;

/** Exact confirmation phrase bound to an action, projection and optional target Generation. */
public record ProjectionStrongConfirmation(
        ProjectionAdministrationAction action,
        ProjectionName projectionName,
        Optional<ProjectionGeneration> generation,
        String phrase) {

    public ProjectionStrongConfirmation {
        action = Objects.requireNonNull(action, "action");
        projectionName = Objects.requireNonNull(projectionName, "projectionName");
        generation = Objects.requireNonNull(generation, "generation");
        phrase = Objects.requireNonNull(phrase, "phrase").strip();
        String expected = expectedPhrase(action, projectionName, generation);
        if (!expected.equals(phrase)) {
            throw new IllegalArgumentException("Projection administration confirmation is invalid");
        }
    }

    public static ProjectionStrongConfirmation confirm(
            ProjectionAdministrationAction action,
            ProjectionName projectionName,
            Optional<ProjectionGeneration> generation) {
        return new ProjectionStrongConfirmation(
                action, projectionName, generation, expectedPhrase(action, projectionName, generation));
    }

    public void require(
            ProjectionAdministrationAction expectedAction,
            ProjectionName expectedProjection,
            Optional<ProjectionGeneration> expectedGeneration) {
        if (action != expectedAction
                || !projectionName.equals(expectedProjection)
                || !generation.equals(expectedGeneration)) {
            throw new IllegalArgumentException(
                    "Projection administration confirmation belongs to another command");
        }
    }

    public static String expectedPhrase(
            ProjectionAdministrationAction action,
            ProjectionName projectionName,
            Optional<ProjectionGeneration> generation) {
        return "CONFIRM_" + Objects.requireNonNull(action, "action").name()
                + ":" + Objects.requireNonNull(projectionName, "projectionName").value()
                + Objects.requireNonNull(generation, "generation")
                        .map(value -> ":" + value.value())
                        .orElse("");
    }
}
