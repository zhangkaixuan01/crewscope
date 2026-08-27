package io.crewscope.application.activity;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityEventId;
import io.crewscope.domain.activity.ActivityViewer;
import io.crewscope.domain.activity.ActivityVisibilityPolicy;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Current-authority application boundary for Team and WorkItem Activity reads. */
public final class ActivityApplicationService {

    private final ActivityQueryPort queries;
    private final TeamRealtimeEventStore realtimeStore;
    private final WorkItemAccessPolicy accessPolicy;
    private final WorkItemRepository workItems;
    private final TeamRoleRepository teamRoles;
    private final MemberRoleRepository memberRoles;
    private final TimeProvider timeProvider;
    private final ActivityVisibilityPolicy visibilityPolicy;

    public ActivityApplicationService(
            ActivityQueryPort queries,
            TeamRealtimeEventStore realtimeStore,
            WorkItemAccessPolicy accessPolicy,
            WorkItemRepository workItems,
            TeamRoleRepository teamRoles,
            MemberRoleRepository memberRoles,
            TimeProvider timeProvider) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.realtimeStore = Objects.requireNonNull(realtimeStore, "realtimeStore");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.workItems = Objects.requireNonNull(workItems, "workItems");
        this.teamRoles = Objects.requireNonNull(teamRoles, "teamRoles");
        this.memberRoles = Objects.requireNonNull(memberRoles, "memberRoles");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.visibilityPolicy = new ActivityVisibilityPolicy();
    }

    /** Validates current Team membership before a snapshot or SSE response can be committed. */
    public void requireTeamAccess(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        authorization(context, organizationId, teamId);
    }

    /** Validates the complete current WorkProject and WorkItem route before cursor decoding. */
    public void requireWorkItemAccess(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId) {
        accessPolicy.requireVisibleWorkItem(
                context, organizationId, teamId, projectId, workItemId);
    }

    public AuthorizedActivitySnapshot teamSnapshot(
            TeamAccessContext context, TeamActivitySnapshotRequest request) {
        Authorization authorization = authorization(
                context, request.organizationId(), request.teamId());
        return authorize(realtimeStore.snapshot(request), authorization);
    }

    public AuthorizedActivitySnapshot workItemSnapshot(
            TeamAccessContext context,
            WorkProjectId projectId,
            WorkItemId workItemId,
            TeamActivitySnapshotRequest request) {
        requireWorkItemRoute(context, projectId, workItemId, request);
        Authorization authorization = authorization(
                context, request.organizationId(), request.teamId());
        return authorize(realtimeStore.snapshot(request), authorization);
    }

    public AuthorizedActivityPage teamHistory(
            TeamAccessContext context, ActivityQuery query) {
        Authorization authorization = authorization(
                context,
                query.cursorScope().organizationId(),
                query.cursorScope().teamId());
        return authorize(queries.find(query), authorization);
    }

    public AuthorizedActivityPage workItemHistory(
            TeamAccessContext context,
            WorkProjectId projectId,
            WorkItemId workItemId,
            ActivityQuery query) {
        requireWorkItemRoute(context, projectId, workItemId, query);
        Authorization authorization = authorization(
                context,
                query.cursorScope().organizationId(),
                query.cursorScope().teamId());
        return authorize(queries.find(query), authorization);
    }

    public ActivityEvent teamDetail(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ActivityEventId eventId) {
        Authorization authorization = authorization(context, organizationId, teamId);
        ActivityEvent event = currentEvent(organizationId, teamId, eventId);
        return requireVisible(event, authorization);
    }

    public ActivityEvent workItemDetail(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            WorkProjectId projectId,
            WorkItemId workItemId,
            ActivityEventId eventId) {
        accessPolicy.requireVisibleWorkItem(
                context, organizationId, teamId, projectId, workItemId);
        ActivityEvent event = currentEvent(organizationId, teamId, eventId);
        if (!event.referencesWorkItem(workItemId)) {
            throw notFound(eventId);
        }
        return requireVisible(event, authorization(context, organizationId, teamId));
    }

    /** Re-resolves membership and roles for every emitted SSE business frame or heartbeat. */
    public boolean canViewNow(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            ActivityEvent event) {
        return canView(Objects.requireNonNull(event, "event"),
                authorization(context, organizationId, teamId));
    }

    private AuthorizedActivitySnapshot authorize(
            TeamActivitySnapshot snapshot, Authorization authorization) {
        List<ActivityEvent> visible = snapshot.events().stream()
                .filter(event -> canView(event, authorization))
                .toList();
        Optional<TeamActivityCursor> nextCursor = snapshot.hasMore()
                ? Optional.of(TeamActivityCursor.from(
                        snapshot.cursorScope(),
                        snapshot.events().get(snapshot.events().size() - 1)))
                : Optional.empty();
        return new AuthorizedActivitySnapshot(
                visible,
                snapshot.hasMore(),
                nextCursor,
                snapshot.snapshotCursor());
    }

    private AuthorizedActivityPage authorize(
            ActivityPage page, Authorization authorization) {
        List<ActivityEvent> visible = page.events().stream()
                .filter(event -> canView(event, authorization))
                .toList();
        return new AuthorizedActivityPage(visible, page.hasMore(), page.nextCursor());
    }

    private ActivityEvent requireVisible(ActivityEvent event, Authorization authorization) {
        if (!canView(event, authorization)) {
            throw notFound(event.id());
        }
        return event;
    }

    private boolean canView(ActivityEvent event, Authorization authorization) {
        Set<WorkItemId> visibleWorkItems = event.restrictedWorkItemId()
                .filter(workItemId -> isVisibleWorkItem(
                        event.organizationId(), event.teamId(), workItemId))
                .map(Set::of)
                .orElseGet(Set::of);
        ActivityViewer viewer = new ActivityViewer(
                authorization.organizationId(),
                authorization.teamId(),
                true,
                authorization.teamAdmin(),
                visibleWorkItems);
        return visibilityPolicy.canView(event, viewer);
    }

    private boolean isVisibleWorkItem(
            OrganizationId organizationId, TeamId teamId, WorkItemId workItemId) {
        return workItems.findById(organizationId, workItemId)
                .filter(item -> item.scope().teamId().equals(teamId))
                .isPresent();
    }

    private Authorization authorization(
            TeamAccessContext context, OrganizationId organizationId, TeamId teamId) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        TeamMember member = accessPolicy.requireVisibleTeamMember(
                trusted, organizationId, teamId);
        UtcTimestamp now = timeProvider.now();
        Map<TeamRoleId, TeamRole> roles = teamRoles.findByTeam(organizationId, teamId).stream()
                .collect(Collectors.toMap(TeamRole::id, role -> role));
        boolean teamAdmin = trusted.platformAdministrator()
                || memberRoles.findByMember(organizationId, member.id()).stream()
                        .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                        .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                        .filter(grant -> grant.isEffectiveAt(now))
                        .map(grant -> roles.get(grant.teamRoleId()))
                        .filter(Objects::nonNull)
                        .filter(TeamRole::isGrantable)
                        .anyMatch(ActivityApplicationService::isAdministratorRole);
        return new Authorization(organizationId, teamId, teamAdmin);
    }

    private static boolean isAdministratorRole(TeamRole role) {
        return role.isBuiltIn(BuiltInTeamRole.TEAM_OWNER)
                || role.isBuiltIn(BuiltInTeamRole.TEAM_ADMIN);
    }

    private void requireWorkItemRoute(
            TeamAccessContext context,
            WorkProjectId projectId,
            WorkItemId workItemId,
            TeamActivitySnapshotRequest request) {
        requireWorkItemFilter(request.filter(), workItemId);
        accessPolicy.requireVisibleWorkItem(
                context,
                request.organizationId(),
                request.teamId(),
                projectId,
                workItemId);
    }

    private void requireWorkItemRoute(
            TeamAccessContext context,
            WorkProjectId projectId,
            WorkItemId workItemId,
            ActivityQuery query) {
        requireWorkItemFilter(query.filter(), workItemId);
        accessPolicy.requireVisibleWorkItem(
                context,
                query.cursorScope().organizationId(),
                query.cursorScope().teamId(),
                projectId,
                workItemId);
    }

    private static void requireWorkItemFilter(ActivityFilter filter, WorkItemId workItemId) {
        if (!filter.workItemId().equals(Optional.of(workItemId))) {
            throw new IllegalArgumentException(
                    "WorkItem Activity query must bind its exact route WorkItem");
        }
    }

    private ActivityEvent currentEvent(
            OrganizationId organizationId, TeamId teamId, ActivityEventId eventId) {
        return queries.findCurrentById(organizationId, teamId, eventId)
                .orElseThrow(() -> notFound(eventId));
    }

    private static AggregateNotFoundException notFound(ActivityEventId eventId) {
        return new AggregateNotFoundException("ActivityEvent", eventId);
    }

    private record Authorization(
            OrganizationId organizationId,
            TeamId teamId,
            boolean teamAdmin) {

        private Authorization {
            organizationId = Objects.requireNonNull(organizationId, "organizationId");
            teamId = Objects.requireNonNull(teamId, "teamId");
        }
    }
}
