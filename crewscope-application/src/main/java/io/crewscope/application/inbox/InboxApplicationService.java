package io.crewscope.application.inbox;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.inbox.InboxItemId;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.team.TeamMember;
import java.util.Objects;
import java.util.Optional;

/** Current-authority read boundary for a member's own Inbox and source navigation. */
public final class InboxApplicationService {

    private final InboxItemQueryPort queries;
    private final WorkItemAccessPolicy accessPolicy;

    public InboxApplicationService(
            InboxItemQueryPort queries, WorkItemAccessPolicy accessPolicy) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    public InboxPage list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxFilter filter,
            Optional<InboxCursor> cursor,
            int limit) {
        TeamMember member = member(context, organizationId, teamId);
        return queries.findCurrentPage(new InboxQuery(
                organizationId, teamId, member.id(), filter, cursor, limit));
    }

    /** Performs the current-member check required before a scope-bearing Cursor is decoded. */
    public void requireAccess(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        member(context, organizationId, teamId);
    }

    public InboxCounts counts(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        TeamMember member = member(context, organizationId, teamId);
        return queries.countCurrent(organizationId, teamId, member.id());
    }

    public InboxItemView detail(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId) {
        TeamMember member = member(context, organizationId, teamId);
        return ownCurrent(organizationId, teamId, member, inboxItemId);
    }

    public InboxSourceTarget target(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            InboxItemId inboxItemId) {
        TeamMember member = member(context, organizationId, teamId);
        InboxItemView item = ownCurrent(organizationId, teamId, member, inboxItemId);
        InboxSourceTarget target = queries.resolveCurrentTarget(
                        organizationId, teamId, member.id(), inboxItemId)
                .filter(value -> value.teamId().equals(teamId))
                .filter(value -> value.sourceId().equals(item.item().source().key().sourceId()))
                .orElseThrow(() -> missing(inboxItemId));
        if (target.projectId().isPresent()) {
            accessPolicy.requireVisibleWorkItem(
                    context,
                    organizationId,
                    teamId,
                    target.projectId().orElseThrow(),
                    target.workItemId().orElseThrow());
        }
        return target;
    }

    private TeamMember member(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        return accessPolicy.requireVisibleTeamMember(context, organizationId, teamId);
    }

    private InboxItemView ownCurrent(
            OrganizationId organizationId,
            TeamId teamId,
            TeamMember member,
            InboxItemId inboxItemId) {
        return queries.findCurrentView(organizationId, teamId, inboxItemId)
                .filter(value -> value.item().memberId().equals(member.id()))
                .orElseThrow(() -> missing(inboxItemId));
    }

    private static AggregateNotFoundException missing(InboxItemId inboxItemId) {
        return new AggregateNotFoundException("InboxItem", inboxItemId);
    }
}
