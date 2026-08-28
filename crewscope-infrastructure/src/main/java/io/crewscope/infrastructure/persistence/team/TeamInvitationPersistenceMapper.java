package io.crewscope.infrastructure.persistence.team;

import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationId;
import io.crewscope.domain.team.TeamInvitationStatus;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reconstitutes V32 invitation aggregates without ever materializing a plaintext bearer token. */
@Component
public final class TeamInvitationPersistenceMapper {

    public TeamInvitation invitation(ResultSet row) throws SQLException {
        return TeamInvitation.reconstitute(
                new TeamInvitationId(uuid(row, "id")),
                new TeamScope(
                        new OrganizationId(uuid(row, "organization_id")),
                        new TeamId(uuid(row, "team_id"))),
                new PrincipalId(uuid(row, "invited_by_principal_id")),
                Optional.ofNullable(row.getString("target_email_normalized"))
                        .map(NormalizedEmail::new),
                BuiltInTeamRole.valueOf(row.getString("target_role")),
                InvitationTokenDigest.fromHex(row.getString("token_digest")),
                time(row, "expires_at"),
                TeamInvitationStatus.valueOf(row.getString("status")),
                optionalUuid(row, "accepted_by_account_id").map(UserAccountId::new),
                optionalUuid(row, "accepted_member_id").map(TeamMemberId::new),
                optionalTime(row, "resolved_at"),
                row.getLong("version"),
                new LifecycleMetadata(time(row, "created_at"), time(row, "updated_at")));
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        return row.getObject(column, UUID.class);
    }

    private static Optional<UUID> optionalUuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, UUID.class));
    }

    private static UtcTimestamp time(ResultSet row, String column) throws SQLException {
        return UtcTimestamp.from(row.getObject(column, OffsetDateTime.class));
    }

    private static Optional<UtcTimestamp> optionalTime(ResultSet row, String column)
            throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class))
                .map(UtcTimestamp::from);
    }
}
