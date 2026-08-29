package io.crewscope.application.team;

import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.TeamInvitationId;
import java.util.Objects;
import java.util.Optional;

/** Token-authorized preview projection that excludes email, digest and inviter identity. */
public record TeamInvitationPreview(
        TeamInvitationPreviewState state,
        Optional<TeamInvitationId> invitationId,
        Optional<String> teamName,
        Optional<BuiltInTeamRole> targetRole,
        Optional<UtcTimestamp> expiresAt,
        boolean targetRestricted) {

    public TeamInvitationPreview {
        state = Objects.requireNonNull(state, "state");
        invitationId = Objects.requireNonNull(invitationId, "invitationId");
        teamName = Objects.requireNonNull(teamName, "teamName");
        targetRole = Objects.requireNonNull(targetRole, "targetRole");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        boolean detailed = state == TeamInvitationPreviewState.AVAILABLE;
        if (invitationId.isPresent() != detailed
                || teamName.isPresent() != detailed
                || targetRole.isPresent() != detailed
                || expiresAt.isPresent() != detailed
                || (!detailed && targetRestricted)) {
            throw new IllegalArgumentException("preview details must exist only while available");
        }
    }

    public static TeamInvitationPreview available(
            TeamInvitationId invitationId,
            String teamName,
            BuiltInTeamRole targetRole,
            UtcTimestamp expiresAt,
            boolean targetRestricted) {
        return new TeamInvitationPreview(
                TeamInvitationPreviewState.AVAILABLE,
                Optional.of(invitationId),
                Optional.of(teamName),
                Optional.of(targetRole),
                Optional.of(expiresAt),
                targetRestricted);
    }

    public static TeamInvitationPreview expired() {
        return empty(TeamInvitationPreviewState.EXPIRED);
    }

    public static TeamInvitationPreview unavailable() {
        return empty(TeamInvitationPreviewState.UNAVAILABLE);
    }

    private static TeamInvitationPreview empty(TeamInvitationPreviewState state) {
        return new TeamInvitationPreview(
                state,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);
    }
}
