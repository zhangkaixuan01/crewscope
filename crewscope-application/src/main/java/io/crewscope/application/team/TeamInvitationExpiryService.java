package io.crewscope.application.team;

import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInvitation;
import java.util.List;
import java.util.Objects;

/** Closes a bounded locked batch of due invitations without deleting historical facts. */
public final class TeamInvitationExpiryService {

    public static final int MAXIMUM_BATCH_SIZE = 500;

    private final TeamInvitationRepository invitations;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public TeamInvitationExpiryService(
            TeamInvitationRepository invitations,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Uses one transaction so SKIP LOCKED claims remain held through every terminal update. */
    public TeamInvitationExpiryResult expireDue(int limit) {
        if (limit < 1 || limit > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return transactions.required(() -> expireLocked(timeProvider.now(), limit));
    }

    private TeamInvitationExpiryResult expireLocked(UtcTimestamp now, int limit) {
        List<TeamInvitation> due = invitations.lockExpiredBatch(now, limit);
        for (TeamInvitation invitation : due) {
            TeamInvitation expired = invitation.expire(now);
            invitations.update(expired, invitation.version());
        }
        return new TeamInvitationExpiryResult(due.size(), due.size() == limit);
    }
}
