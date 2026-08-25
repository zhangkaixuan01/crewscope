package io.crewscope.application.inbox;

import io.crewscope.domain.inbox.InboxItem;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.Optional;

/** Read boundary resolving an Inbox item from the current projection generation only. */
public interface InboxItemQueryPort {

    Optional<InboxItem> findCurrent(
            OrganizationId organizationId, TeamId teamId, InboxItemId inboxItemId);
}
