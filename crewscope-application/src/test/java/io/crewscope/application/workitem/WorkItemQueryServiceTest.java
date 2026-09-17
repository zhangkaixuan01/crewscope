package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemTransitionCatalog;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Proves membership-gated WorkItem pages and consistent collaboration snapshots. */
class WorkItemQueryServiceTest {

  @Test
  void passesProjectStatusCursorAndLimitToTheRepository() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    WorkItemQueryService service = service(fixture);
    WorkItemCursor cursor = new WorkItemCursor(fixture.item.audit().updatedAt(), fixture.item.id());

    WorkItemListPage page =
        service.list(
            fixture.access(),
            fixture.organizationId,
            fixture.initialization.team().id(),
            fixture.project.id(),
            Optional.of(WorkItemStatus.BACKLOG),
            Optional.of(cursor),
            25);

    assertEquals(1, page.items().size());
    assertEquals(fixture.item.id(), page.items().get(0).workItem().id());
    assertEquals(Optional.of(fixture.project.id()), fixture.lastQuery.projectId());
    assertEquals(Optional.of(WorkItemStatus.BACKLOG), fixture.lastQuery.status());
    assertEquals(Optional.of(cursor), fixture.lastQuery.cursor());
    assertEquals(25, fixture.lastQuery.limit());
  }

  @Test
  void givesEveryListRowTheAdjacentEdgesOfItsOwnStatus() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();

    WorkItemListPage page =
        service(fixture)
            .list(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                fixture.project.id(),
                Optional.empty(),
                Optional.empty(),
                25);

    List<WorkItemAvailableTransition> actions = page.items().get(0).availableActions();

    // Backlog is not terminal, so it has edges; a list row carries all of them, enabled, because this
    // member holds a team-scoped grant covering every project.
    assertEquals(
        WorkItemTransitionCatalog.from(WorkItemStatus.BACKLOG).size(), actions.size());
    assertTrue(actions.stream().allMatch(WorkItemAvailableTransition::enabled));
    assertTrue(actions.stream().allMatch(action -> action.reason().isEmpty()));
  }

  /**
   * The list carries the full edge list, not the WorkDesk's executable subset: a row renders a menu,
   * and a menu has room to explain a disabled action. A list that silently dropped it would leave the
   * row and the detail panel disagreeing about what exists.
   */
  @Test
  void keepsUnavailableEdgesOnTheRowWithTheirReason() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    // A grant scoped to some other WorkProject: the member may see this project but not participate.
    fixture.useProjectRole(WorkProjectId.generate());

    WorkItemListPage page =
        service(fixture)
            .list(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                fixture.project.id(),
                Optional.empty(),
                Optional.empty(),
                25);

    List<WorkItemAvailableTransition> actions = page.items().get(0).availableActions();

    assertEquals(
        WorkItemTransitionCatalog.from(WorkItemStatus.BACKLOG).size(),
        actions.size(),
        "an unavailable edge must stay visible, not disappear");
    assertTrue(actions.stream().noneMatch(WorkItemAvailableTransition::enabled));
    assertEquals(
        Set.of(TransitionBlockReason.PERMISSION_DENIED),
        actions.stream()
            .map(action -> action.reason().orElseThrow())
            .collect(Collectors.toSet()));
  }

  /**
   * The budget gate: one read of the member's roles and grants for the whole page, and never a
   * fallback to the single-project permission check. Three rows, one project — a per-row policy call
   * would make these three, which is the N+1 that would make a board of 500 items unusable.
   */
  @Test
  void resolvesThePermissionOnceForTheWholePage() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    fixture.create(secondItem(fixture, "CRW-2"));
    fixture.create(secondItem(fixture, "CRW-3"));
    AtomicInteger roleReads = new AtomicInteger();
    AtomicInteger grantReads = new AtomicInteger();
    WorkItemQueryService service = serviceCountingPermissionReads(fixture, roleReads, grantReads);

    WorkItemListPage page =
        service.list(
            fixture.access(),
            fixture.organizationId,
            fixture.initialization.team().id(),
            fixture.project.id(),
            Optional.empty(),
            Optional.empty(),
            25);

    assertEquals(3, page.items().size());
    assertEquals(1, roleReads.get(), "role definitions must be read once per request");
    assertEquals(1, grantReads.get(), "member grants must be read once per request");
    List<WorkItemAvailableTransition> first = page.items().get(0).availableActions();
    assertTrue(
        page.items().stream().allMatch(row -> row.availableActions().equals(first)),
        "one verdict for one project, reused by every row");
  }

  @Test
  void returnsTheWorkItemCommentsAndResourceLinksAsOneSnapshot() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    fixture.comments.add(
        WorkItemComment.addNative(
            WorkItemCommentId.generate(),
            fixture.item,
            fixture.actor,
            "Review the contract",
            fixture.NOW));
    fixture.links.add(
        WorkItemResourceLink.link(
            WorkItemResourceLinkId.generate(),
            fixture.item,
            WorkItemResourceType.EXTERNAL_URL,
            "https://example.com/spec",
            Optional.of("Spec"),
            fixture.actor,
            fixture.NOW));

    WorkItemDetails details =
        service(fixture)
            .get(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                fixture.project.id(),
                fixture.item.id());

    assertEquals(fixture.item.id(), details.workItem().id());
    assertEquals("Review the contract", details.comments().get(0).content());
    assertEquals("https://example.com/spec", details.resourceLinks().get(0).resourceReference());
    // The panel no longer needs a second round trip that could observe a different verdict.
    assertFalse(details.availableActions().isEmpty());
    assertTrue(details.availableActions().stream().allMatch(WorkItemAvailableTransition::enabled));
  }

  @Test
  void rejectsSuspendedMembershipAndMismatchedUrlScopes() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    fixture.members =
        java.util.List.of(
            fixture.initialization.ownerMember().suspend(
                UtcTimestamp.parse("2026-08-08T09:01:00Z")));

    assertThrows(
        PolicyDeniedException.class,
        () ->
            service(fixture)
                .get(
                    fixture.access(),
                    fixture.organizationId,
                    fixture.initialization.team().id(),
                    fixture.project.id(),
                    fixture.item.id()));

    WorkItemCollaborationTestFixture scoped = new WorkItemCollaborationTestFixture();
    assertThrows(
        AggregateNotFoundException.class,
        () ->
            service(scoped)
                .get(
                    scoped.access(),
                    scoped.organizationId,
                    TeamId.generate(),
                    scoped.project.id(),
                    scoped.item.id()));
    assertThrows(
        AggregateNotFoundException.class,
        () ->
            service(scoped)
                .get(
                    scoped.access(),
                    scoped.organizationId,
                    scoped.initialization.team().id(),
                    WorkProjectId.generate(),
                    scoped.item.id()));
  }

  /** A second native WorkItem in the fixture's project, so a page has more than one row. */
  private static WorkItem secondItem(WorkItemCollaborationTestFixture fixture, String key) {
    return WorkItem.createNative(
        WorkItemId.generate(),
        fixture.project,
        new WorkItemKey(key),
        WorkItemType.FEATURE,
        "Row " + key,
        Optional.empty(),
        WorkItemPriority.MEDIUM,
        Set.of(),
        Optional.empty(),
        fixture.actor,
        fixture.NOW);
  }

  private static WorkItemQueryService service(WorkItemCollaborationTestFixture fixture) {
    return new WorkItemQueryService(
        fixture,
        fixture.commentRepository,
        fixture.linkRepository,
        fixture.accessPolicy(),
        new WorkItemTransitionAvailabilityProjector(),
        fixture,
        () -> WorkItemCollaborationTestFixture.NOW);
  }

  /**
   * The same service, with both permission sources counted.
   *
   * <p>Mocking the policy and verifying the call would only prove which method ran; counting the
   * repository reads proves the thing the budget actually cares about — how many times the member's
   * roles and grants were fetched.
   */
  private static WorkItemQueryService serviceCountingPermissionReads(
      WorkItemCollaborationTestFixture fixture, AtomicInteger roleReads, AtomicInteger grantReads) {
    TeamRoleRepository countingRoles =
        new TeamRoleRepository() {
          @Override
          public List<TeamRole> createAll(List<TeamRole> roles) {
            return fixture.roleRepository.createAll(roles);
          }

          @Override
          public List<TeamRole> findByTeam(OrganizationId organizationId, TeamId teamId) {
            roleReads.incrementAndGet();
            return fixture.roleRepository.findByTeam(organizationId, teamId);
          }
        };
    MemberRoleRepository countingGrants =
        new MemberRoleRepository() {
          @Override
          public MemberRole create(MemberRole memberRole) {
            return fixture.create(memberRole);
          }

          @Override
          public List<MemberRole> findByMember(OrganizationId organizationId, TeamMemberId memberId) {
            grantReads.incrementAndGet();
            return fixture.findByMember(organizationId, memberId);
          }
        };
    WorkItemAccessPolicy countingPolicy =
        new WorkItemAccessPolicy(
            fixture,
            fixture.projectRepository,
            fixture,
            fixture.membershipQuery(),
            countingRoles,
            countingGrants);
    return new WorkItemQueryService(
        fixture,
        fixture.commentRepository,
        fixture.linkRepository,
        countingPolicy,
        new WorkItemTransitionAvailabilityProjector(),
        fixture,
        () -> WorkItemCollaborationTestFixture.NOW);
  }
}
