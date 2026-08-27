package io.crewscope.application.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.activity.ActivityActor;
import io.crewscope.domain.activity.ActivityCategory;
import io.crewscope.domain.activity.ActivityEvent;
import io.crewscope.domain.activity.ActivityPayloadSchema;
import io.crewscope.domain.activity.ActivityReference;
import io.crewscope.domain.activity.ActivityReferenceType;
import io.crewscope.domain.activity.ActivitySubject;
import io.crewscope.domain.activity.ActivitySubjectType;
import io.crewscope.domain.activity.ActivityVisibility;
import io.crewscope.domain.activity.TeamSequence;
import io.crewscope.domain.projection.ProjectionGeneration;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.workitem.WorkItemId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Current-authority, visibility and scanned-position proof for M6-A01. */
class ActivityApplicationServiceM6A01Test {

  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final TeamId TEAM_ID = TeamId.generate();
  private static final WorkItemId WORK_ITEM_ID = WorkItemId.generate();
  private static final ProjectionName PROJECTION = new ProjectionName("team-activity");
  private static final UtcTimestamp NOW = UtcTimestamp.from(Instant.parse("2026-08-27T00:00:00Z"));

  private ActivityQueryPort queries;
  private TeamRealtimeEventStore realtimeStore;
  private WorkItemAccessPolicy accessPolicy;
  private WorkItemRepository workItems;
  private TeamRoleRepository teamRoles;
  private MemberRoleRepository memberRoles;
  private TeamAccessContext access;
  private TeamMember member;
  private ActivityApplicationService service;

  @BeforeEach
  void setUp() {
    queries = mock(ActivityQueryPort.class);
    realtimeStore = mock(TeamRealtimeEventStore.class);
    accessPolicy = mock(WorkItemAccessPolicy.class);
    workItems = mock(WorkItemRepository.class);
    teamRoles = mock(TeamRoleRepository.class);
    memberRoles = mock(MemberRoleRepository.class);
    access = mock(TeamAccessContext.class);
    member = mock(TeamMember.class);
    when(member.id()).thenReturn(TeamMemberId.generate());
    when(accessPolicy.requireVisibleTeamMember(access, ORGANIZATION_ID, TEAM_ID))
        .thenReturn(member);
    when(teamRoles.findByTeam(ORGANIZATION_ID, TEAM_ID)).thenReturn(List.of());
    when(memberRoles.findByMember(any(), any())).thenReturn(List.of());
    when(workItems.findById(ORGANIZATION_ID, WORK_ITEM_ID)).thenReturn(Optional.empty());
    service = new ActivityApplicationService(
        queries,
        realtimeStore,
        accessPolicy,
        workItems,
        teamRoles,
        memberRoles,
        () -> NOW);
  }

  @Test
  void filtersRestrictedRowsButKeepsTheUnderlyingDurableContinuation() {
    ActivityEvent visible = event(1, ActivityVisibility.TEAM_MEMBERS);
    ActivityEvent hidden = event(2, ActivityVisibility.TEAM_ADMINS);
    ActivityQuery query = query(2);
    when(queries.find(query)).thenReturn(new ActivityPage(query, List.of(visible, hidden), true));

    AuthorizedActivityPage result = service.teamHistory(access, query);

    assertEquals(List.of(visible), result.events());
    assertTrue(result.hasMore());
    assertEquals(hidden.id(), result.nextCursor().orElseThrow().eventId());
  }

  @Test
  void treatsAnInvisibleDetailAsMissingAndLetsAPlatformAdministratorReadIt() {
    ActivityEvent hidden = event(3, ActivityVisibility.TEAM_ADMINS);
    when(queries.findCurrentById(ORGANIZATION_ID, TEAM_ID, hidden.id()))
        .thenReturn(Optional.of(hidden));

    assertThrows(
        AggregateNotFoundException.class,
        () -> service.teamDetail(access, ORGANIZATION_ID, TEAM_ID, hidden.id()));

    when(access.platformAdministrator()).thenReturn(true);
    assertEquals(
        hidden,
        service.teamDetail(access, ORGANIZATION_ID, TEAM_ID, hidden.id()));
  }

  @Test
  void recognizesOnlyAnEffectiveTeamWideBuiltInAdministratorGrant() {
    ActivityEvent adminOnly = event(5, ActivityVisibility.TEAM_ADMINS);
    TeamRoleId roleId = TeamRoleId.generate();
    TeamRole administrator = mock(TeamRole.class);
    when(administrator.id()).thenReturn(roleId);
    when(administrator.isGrantable()).thenReturn(true);
    when(administrator.isBuiltIn(BuiltInTeamRole.TEAM_ADMIN)).thenReturn(true);
    MemberRole grant = mock(MemberRole.class);
    when(grant.status()).thenReturn(MemberRoleStatus.ACTIVE);
    when(grant.roleScope()).thenReturn(RoleScope.team());
    when(grant.isEffectiveAt(NOW)).thenReturn(true);
    when(grant.teamRoleId()).thenReturn(roleId);
    when(teamRoles.findByTeam(ORGANIZATION_ID, TEAM_ID)).thenReturn(List.of(administrator));
    when(memberRoles.findByMember(ORGANIZATION_ID, member.id())).thenReturn(List.of(grant));
    when(queries.findCurrentById(ORGANIZATION_ID, TEAM_ID, adminOnly.id()))
        .thenReturn(Optional.of(adminOnly));

    assertEquals(
        adminOnly,
        service.teamDetail(access, ORGANIZATION_ID, TEAM_ID, adminOnly.id()));
  }

  @Test
  void reevaluatesParticipantVisibilityAndCurrentMembershipForEveryFrame() {
    ActivityEvent participant = event(4, ActivityVisibility.WORK_ITEM_PARTICIPANTS);

    assertFalse(service.canViewNow(access, ORGANIZATION_ID, TEAM_ID, participant));
    var item = mock(io.crewscope.domain.workitem.WorkItem.class);
    var scope = new io.crewscope.domain.workitem.WorkItemScope(
        ORGANIZATION_ID,
        TEAM_ID,
        io.crewscope.domain.shared.id.WorkspaceId.generate(),
        io.crewscope.domain.workitem.WorkProjectId.generate());
    when(item.scope()).thenReturn(scope);
    when(workItems.findById(ORGANIZATION_ID, WORK_ITEM_ID)).thenReturn(Optional.of(item));
    assertTrue(service.canViewNow(access, ORGANIZATION_ID, TEAM_ID, participant));

    when(accessPolicy.requireVisibleTeamMember(access, ORGANIZATION_ID, TEAM_ID))
        .thenThrow(new PolicyDeniedException("access this Team's Activity"));
    assertThrows(
        PolicyDeniedException.class,
        () -> service.canViewNow(access, ORGANIZATION_ID, TEAM_ID, participant));
  }

  private static ActivityQuery query(int limit) {
    ActivityCursorScope scope = ActivityCursorScope.of(
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION,
        ProjectionGeneration.FIRST,
        SchemaVersion.V1,
        ActivityFilter.ALL);
    return new ActivityQuery(scope, ActivityFilter.ALL, Optional.empty(), limit);
  }

  private static ActivityEvent event(long sequence, ActivityVisibility visibility) {
    byte[] identity = ("m6-a01-" + sequence).getBytes(StandardCharsets.UTF_8);
    ActivityPayloadSchema schema = new ActivityPayloadSchema(
        "activity.work-item-created",
        SchemaVersion.V1,
        Set.of("itemKey"),
        Set.of("title"));
    return ActivityEvent.project(
        java.util.UUID.nameUUIDFromBytes(identity),
        ORGANIZATION_ID,
        TEAM_ID,
        PROJECTION,
        ProjectionGeneration.FIRST,
        SchemaVersion.V1,
        new TeamSequence(sequence),
        new EventType("WORK_ITEM_CREATED"),
        ActivityCategory.WORK_ITEM,
        visibility,
        new ActivitySubject(ActivitySubjectType.WORK_ITEM, WORK_ITEM_ID.value()),
        new ActivityActor(EventActorType.USER, Optional.of(PrincipalId.generate())),
        List.of(
            new ActivityReference(ActivityReferenceType.TEAM, TEAM_ID.value()),
            new ActivityReference(ActivityReferenceType.WORK_ITEM, WORK_ITEM_ID.value())),
        NOW,
        schema.createPayload(Map.of("itemKey", "CS-" + sequence, "title", "Activity")));
  }
}
