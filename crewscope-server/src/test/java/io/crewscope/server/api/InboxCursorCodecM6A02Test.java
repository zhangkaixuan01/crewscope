package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.inbox.InboxCursor;
import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.domain.inbox.InboxDispositionStatus;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.inbox.InboxPriority;
import io.crewscope.domain.inbox.InboxSourceStatus;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Canonical route/filter binding for M6-A02 member Inbox cursors. */
class InboxCursorCodecM6A02Test {

    @Test
    void roundTripsAndRejectsAnotherRouteOrFilter() {
        InboxCursorCodec codec = new InboxCursorCodec();
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        InboxFilter filter = new InboxFilter(
                Set.of(InboxItemType.REVIEW),
                Set.of(InboxSourceStatus.OPEN),
                Set.of(InboxDispositionStatus.UNREAD));
        InboxCursor cursor = new InboxCursor(
                new ProjectionGeneration(3),
                InboxPriority.HIGH,
                Optional.of(UtcTimestamp.from(Instant.parse("2026-08-28T00:00:00Z"))),
                UtcTimestamp.from(Instant.parse("2026-08-27T00:00:00Z")),
                new InboxItemId(java.util.UUID.randomUUID()));
        String token = codec.encode(cursor, organizationId, teamId, filter);

        assertEquals(cursor, codec.decode(token, organizationId, teamId, filter));
        assertThrows(
                ApiRequestException.class,
                () -> codec.decode(token, organizationId, TeamId.generate(), filter));
        assertThrows(
                ApiRequestException.class,
                () -> codec.decode(token, organizationId, teamId, InboxFilter.OPEN));
    }
}
