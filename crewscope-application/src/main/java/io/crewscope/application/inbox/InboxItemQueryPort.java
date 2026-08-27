package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Optional;

/** Read boundary resolving an Inbox item from the current projection generation only. */
public interface InboxItemQueryPort {

    Optional<InboxItem> findCurrent(
            OrganizationId organizationId, TeamId teamId, InboxItemId inboxItemId);

    /**
     * Returns the current source merged with Generation-independent member authority. Adapters
     * must override this method; the default keeps small domain/application test doubles source
     * compatible and represents an absent authority row as UNREAD@0.
     */
    default Optional<InboxItemView> findCurrentView(
            OrganizationId organizationId, TeamId teamId, InboxItemId inboxItemId) {
        return findCurrent(organizationId, teamId, inboxItemId)
                .map(item -> InboxItemView.merge(item, Optional.empty()));
    }

    /** Returns one generation-bound Keyset page for the exact current member. */
    default InboxPage findCurrentPage(InboxQuery query) {
        throw new UnsupportedOperationException("Inbox page queries are not implemented");
    }

    /** Counts only open, non-archived current-generation items for the exact member. */
    default InboxCounts countCurrent(
            OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
        throw new UnsupportedOperationException("Inbox count queries are not implemented");
    }

    /** Resolves a source to a closed internal target using current authoritative joins. */
    default Optional<InboxSourceTarget> resolveCurrentTarget(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMemberId memberId,
            InboxItemId inboxItemId) {
        throw new UnsupportedOperationException("Inbox target resolution is not implemented");
    }
}
