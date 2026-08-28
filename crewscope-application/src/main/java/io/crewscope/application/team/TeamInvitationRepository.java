package io.crewscope.application.team;

import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.InvitationTokenDigest;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationConflictException;
import io.crewscope.domain.team.TeamInvitationId;
import java.util.List;
import java.util.Optional;

/** Persistence port for one-time Team invitations and token-digest uniqueness. */
public interface TeamInvitationRepository {

    Optional<TeamInvitation> findById(
            OrganizationId organizationId, TeamInvitationId invitationId);

    /** Public preview lookup; adapters must never return or log a plaintext token. */
    Optional<TeamInvitation> findByTokenDigest(InvitationTokenDigest tokenDigest);

    /** Acceptance lookup with a database row lock held until the surrounding transaction ends. */
    Optional<TeamInvitation> lockByTokenDigest(InvitationTokenDigest tokenDigest);

    /** Returns one bounded newest-first keyset page for Team invitation management. */
    TeamInvitationPage findByTeam(
            OrganizationId organizationId,
            TeamId teamId,
            Optional<TeamInvitationCursor> cursor,
            int limit);

    /** Locks a bounded due batch with FOR UPDATE SKIP LOCKED for terminal expiry updates. */
    List<TeamInvitation> lockExpiredBatch(UtcTimestamp now, int limit);

    TeamInvitation create(TeamInvitation invitation) throws TeamInvitationConflictException;

    TeamInvitation update(TeamInvitation invitation, long expectedVersion)
            throws TeamInvitationConflictException, OptimisticLockConflictException;
}
