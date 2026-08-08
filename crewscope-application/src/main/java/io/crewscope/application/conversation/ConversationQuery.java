package io.crewscope.application.conversation;

import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;
import java.util.Optional;

/** Tenant-scoped keyset query for Conversation management and Personal Agent history. */
public record ConversationQuery(
        OrganizationId organizationId,
        TeamId teamId,
        Optional<TeamMemberId> ownerMemberId,
        Optional<ConversationStatus> status,
        Optional<ConversationListCursor> cursor,
        int limit) {

    public ConversationQuery {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        ownerMemberId = Objects.requireNonNull(ownerMemberId, "ownerMemberId");
        status = Objects.requireNonNull(status, "status");
        cursor = Objects.requireNonNull(cursor, "cursor");
        if (limit < 1 || limit > 100) {
            throw new DomainValidationException("conversationQuery.limit", "must be between 1 and 100");
        }
    }
}
