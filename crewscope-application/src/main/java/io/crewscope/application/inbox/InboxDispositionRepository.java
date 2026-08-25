package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxDisposition;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;

/** Generation-independent persistence boundary for member-owned Inbox dispositions. */
public interface InboxDispositionRepository {

    Optional<InboxDisposition> find(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            InboxItemId inboxItemId);

    /**
     * Inserts at expected version 0 or conditionally updates the exact committed version. This
     * compare-and-set is required even though the aggregate also validates its in-memory version.
     */
    void save(InboxDisposition disposition, long expectedVersion);
}
