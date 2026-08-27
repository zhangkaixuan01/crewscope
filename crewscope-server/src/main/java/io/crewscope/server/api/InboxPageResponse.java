package io.crewscope.server.api;

import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.application.inbox.InboxPage;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.util.List;
import java.util.Objects;

/** Public Inbox page with one opaque generation-bound continuation. */
public record InboxPageResponse(List<InboxItemResponse> items, String nextCursor) {

    static InboxPageResponse from(
            InboxPage page,
            InboxCursorCodec codec,
            OrganizationId organizationId,
            TeamId teamId,
            InboxFilter filter) {
        InboxPage value = Objects.requireNonNull(page, "page");
        return new InboxPageResponse(
                value.items().stream().map(InboxItemResponse::from).toList(),
                value.nextCursor()
                        .map(cursor -> codec.encode(cursor, organizationId, teamId, filter))
                        .orElse(null));
    }
}
