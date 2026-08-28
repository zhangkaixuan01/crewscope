package io.crewscope.application.team;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.NormalizedEmail;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Issues one durable invitation while returning its plaintext bearer secret exactly once. */
public final class TeamInvitationIssueService {

    private static final Duration MINIMUM_TTL = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_TTL = Duration.ofDays(30);

    private final TeamInvitationRepository invitations;
    private final InvitationTokenGenerator tokens;
    private final InvitationTokenDigester digester;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public TeamInvitationIssueService(
            TeamInvitationRepository invitations,
            InvitationTokenGenerator tokens,
            InvitationTokenDigester digester,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Persists only the digest; the returned result is the sole plaintext recovery opportunity. */
    public TeamInvitationIssueResult issue(
            Team team,
            Principal invitedBy,
            Optional<NormalizedEmail> targetEmail,
            BuiltInTeamRole targetRole,
            Duration ttl) {
        Team requiredTeam = Objects.requireNonNull(team, "team");
        Principal requiredInviter = Objects.requireNonNull(invitedBy, "invitedBy");
        Optional<NormalizedEmail> requiredEmail = Objects.requireNonNull(targetEmail, "targetEmail");
        BuiltInTeamRole requiredRole = Objects.requireNonNull(targetRole, "targetRole");
        Duration requiredTtl = requireTtl(ttl);
        InvitationToken token = Objects.requireNonNull(tokens.generate(), "generated token");
        UtcTimestamp now = timeProvider.now();
        UtcTimestamp expiresAt = UtcTimestamp.from(now.value().plus(requiredTtl));
        TeamInvitation invitation = TeamInvitation.issue(
                TeamInvitationId.generate(),
                requiredTeam,
                requiredInviter,
                requiredEmail,
                requiredRole,
                digester.digest(token),
                expiresAt,
                now);
        TeamInvitation persisted = transactions.required(() -> invitations.create(invitation));
        requirePersistedIdentity(invitation, persisted);
        return new TeamInvitationIssueResult(persisted, token);
    }

    private static Duration requireTtl(Duration value) {
        Duration required = Objects.requireNonNull(value, "ttl");
        if (required.compareTo(MINIMUM_TTL) < 0 || required.compareTo(MAXIMUM_TTL) > 0) {
            throw new IllegalArgumentException("ttl must be between one minute and 30 days");
        }
        return required;
    }

    private static void requirePersistedIdentity(
            TeamInvitation expected, TeamInvitation persisted) {
        TeamInvitation required = Objects.requireNonNull(persisted, "persisted invitation");
        if (!required.id().equals(expected.id())
                || !required.scope().equals(expected.scope())
                || !required.tokenDigest().matches(expected.tokenDigest())
                || required.version() != expected.version()) {
            throw new IllegalStateException("Invitation repository returned a different aggregate");
        }
    }
}
