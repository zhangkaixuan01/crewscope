package io.crewscope.application.team;

import io.crewscope.domain.team.Team;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Untrusted user input for creating a Team foundation. */
public record CreateTeamCommand(
        @NotBlank @Size(max = Team.MAX_NAME_LENGTH) String name) {}
