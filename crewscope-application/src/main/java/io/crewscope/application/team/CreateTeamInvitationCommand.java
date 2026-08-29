package io.crewscope.application.team;

import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.team.BuiltInTeamRole;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Product-owned invitation inputs; bearer-token creation stays inside the application service. */
public record CreateTeamInvitationCommand(
        Optional<NormalizedEmail> targetEmail, BuiltInTeamRole targetRole, Duration ttl) {

    public CreateTeamInvitationCommand {
        targetEmail = Objects.requireNonNull(targetEmail, "targetEmail");
        targetRole = Objects.requireNonNull(targetRole, "targetRole");
        ttl = Objects.requireNonNull(ttl, "ttl");
    }
}
