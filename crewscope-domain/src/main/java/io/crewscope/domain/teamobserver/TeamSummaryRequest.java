package io.crewscope.domain.teamobserver;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Objects;

/** Bounded member-scoped request for the five-section Team Observer summary. */
public record TeamSummaryRequest(
        OrganizationId organizationId,
        TeamId teamId,
        TeamMemberId requestingMemberId,
        int maxItemsPerSection) {

    public static final int MAX_ITEMS_PER_SECTION = 50;

    public TeamSummaryRequest {
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        requestingMemberId = Objects.requireNonNull(requestingMemberId, "requestingMemberId");
        if (maxItemsPerSection < 1 || maxItemsPerSection > MAX_ITEMS_PER_SECTION) {
            throw new DomainValidationException(
                    "teamSummary.maxItemsPerSection", "must be between 1 and 50");
        }
    }

    /** Rechecks current membership before any Team projection can be read. */
    public TeamSummaryRequest requireAuthorizedMember(TeamMember member) {
        TeamMember required = Objects.requireNonNull(member, "member");
        if (!required.canParticipate()
                || !requestingMemberId.equals(required.id())
                || !organizationId.equals(required.scope().organizationId())
                || !teamId.equals(required.scope().teamId())) {
            throw new DomainValidationException(
                    "teamSummary.requestingMemberId",
                    "must be the current active member requesting this Team summary");
        }
        return this;
    }
}
