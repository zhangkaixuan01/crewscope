package io.crewscope.application.workdesk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemAvailableTransition;
import io.crewscope.application.workitem.WorkItemBlockedReason;
import io.crewscope.application.workitem.WorkItemExecutionSummary;
import io.crewscope.application.workitem.WorkItemSummaryRepository;
import io.crewscope.application.workitem.WorkItemTransitionAvailabilityProjector;
import io.crewscope.application.workitem.WorkItemTransitionPermissionResolver;
import io.crewscope.application.workitem.WorkItemTransitionSubject;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.task.TaskExecutionStatus;
import io.crewscope.domain.task.TaskExecutionWaitReason;
import io.crewscope.domain.task.TaskId;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies WorkDesk delegates authorization and keeps the member identity server-derived. */
class WorkDeskQueryServiceTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-13T00:00:00Z");

  @Test
  void authorizesTheTeamBeforeBuildingTheMemberScopedQuery() {
    Fixture fixture = new Fixture();
    WorkDeskSummary expected = fixture.summary(List.of());

    WorkDeskSummary actual = fixture.summarize();

    // Equal, not identical: filling availability rebuilds each section, so the value the repository
    // returned is no longer the value that leaves the service. What must not change is the scope and
    // the filters, which the next test pins.
    assertEquals(expected, actual);
    verify(fixture.itemPolicy).requireVisibleTeamMember(fixture.context, fixture.organizationId, fixture.teamId);
    verify(fixture.repository).summarize(any(WorkDeskQuery.class));
  }

  @Test
  void passesTheValidatedScopeAndFiltersToTheRepository() {
    Fixture fixture = new Fixture();
    WorkProjectId projectId = WorkProjectId.generate();
    fixture.summary(List.of());

    fixture.summarize(Optional.of(projectId));

    org.mockito.ArgumentCaptor<WorkDeskQuery> captor = org.mockito.ArgumentCaptor.forClass(WorkDeskQuery.class);
    verify(fixture.repository).summarize(captor.capture());
    WorkDeskQuery query = captor.getValue();
    assertEquals(projectId, query.projectId().orElseThrow());
    assertTrue(query.onlyNeedsAction());
    assertEquals(fixture.member.id(), query.memberId());
    assertEquals(fixture.principalId, query.principalId());
    assertEquals(WorkDeskQuery.DEFAULT_SECTION_LIMIT, query.sectionLimit());
  }

  @Test
  void layersRowFactsAcrossEverySectionInOneBatch() {
    Fixture fixture = new Fixture();
    WorkItemId firstItem = WorkItemId.generate();
    WorkItemId secondItem = WorkItemId.generate();
    PrincipalId blocking = PrincipalId.generate();
    fixture.summaryRepositoryAnswers(Map.of(
        firstItem, blockedSummary(firstItem, blocking),
        secondItem, WorkItemExecutionSummary.none(
            secondItem, 2L, WorkItemStatus.ARCHIVED, NOW)));
    when(fixture.repository.summarize(any(WorkDeskQuery.class))).thenReturn(new WorkDeskSummary(
        fixture.organizationId.toString(),
        fixture.teamId.toString(),
        Optional.empty(),
        Instant.parse("2026-09-13T00:00:00Z"),
        List.of(
            new WorkDeskSection("WORK_ITEM", "我的工作项", 4, 1, false,
                List.of(workItemRow(firstItem, WorkItemStatus.IN_PROGRESS))),
            new WorkDeskSection("REVIEW", "待我评审", 3, 2, false, List.of(
                workItemRow(secondItem, WorkItemStatus.ARCHIVED),
                row("INBOX", "inbox-a", null, null, false))))));

    WorkDeskSummary summary = fixture.summarize();

    // The batch is per answer, not per section or per row: two WorkItem rows in two different
    // sections cost one summary call, and the INBOX row without a WorkItem is passed through.
    verify(fixture.summaryRepository, times(1)).summarize(
        eq(fixture.organizationId), eq(fixture.teamId), eq(Optional.empty()), any(), any());
    WorkDeskItem first = summary.sections().get(0).items().get(0);
    assertTrue(first.rowSummary().isPresent(), "the WorkItem row must carry its summary");
    assertEquals(blocking.toString(), first.waitingOn().orElseThrow().principalId());
    assertTrue(first.waitingOn().orElseThrow().displayName().isEmpty());
    WorkDeskItem second = summary.sections().get(1).items().get(0);
    assertTrue(second.rowSummary().isPresent());
    assertTrue(second.waitingOn().isEmpty());
    WorkDeskItem inbox = summary.sections().get(1).items().get(1);
    assertTrue(inbox.workItemId().isEmpty() && inbox.rowSummary().isEmpty());
  }

  @Test
  void continuesOneSectionFromItsPositionWithTheValidatedLimit() {
    Fixture fixture = new Fixture();
    WorkDeskSectionPosition position =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T08:00:00Z"), UUID.randomUUID());
    WorkDeskSectionPosition next =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T07:00:00Z"), UUID.randomUUID());
    when(fixture.repository.summarizeSection(any(), eq(position))).thenReturn(new WorkDeskSection(
        "WORK_ITEM", "我的工作项", 4, 30, true,
        List.of(row("WORK_ITEM", "item-a", WorkProjectId.generate(), WorkItemStatus.IN_PROGRESS, true)),
        Optional.of(next)));

    WorkDeskSection section = fixture.service.summarizeSection(
        fixture.context, fixture.organizationId, fixture.teamId, Optional.empty(), Optional.empty(),
        true, 25, "WORK_ITEM", position);

    assertEquals(30, section.total());
    assertEquals(next, section.nextPosition().orElseThrow());
    org.mockito.ArgumentCaptor<WorkDeskQuery> captor = org.mockito.ArgumentCaptor.forClass(WorkDeskQuery.class);
    verify(fixture.repository).summarizeSection(captor.capture(), eq(position));
    assertEquals(25, captor.getValue().sectionLimit());
    verify(fixture.itemPolicy, times(1))
        .resolvePermission(eq(fixture.context), eq(fixture.organizationId), eq(fixture.teamId), any());
  }

  @Test
  void refusesASectionContinuationWhoseCursorBelongsToAnotherSection() {
    Fixture fixture = new Fixture();
    WorkDeskSectionPosition position =
        WorkDeskSectionPosition.of("WORK_ITEM", Instant.parse("2026-09-13T08:00:00Z"), UUID.randomUUID());

    assertThrows(IllegalArgumentException.class, () -> fixture.service.summarizeSection(
        fixture.context, fixture.organizationId, fixture.teamId, Optional.empty(), Optional.empty(),
        true, 20, "REVIEW", position));
    verify(fixture.repository, never()).summarizeSection(any(), any());
  }

  @Test
  void fillsOnlyWorkItemRowsAndResolvesThePermissionOncePerRequest() {
    Fixture fixture = new Fixture();
    WorkProjectId first = WorkProjectId.generate();
    WorkProjectId second = WorkProjectId.generate();
    WorkProjectId third = WorkProjectId.generate();
    fixture.summary(List.of(
        row("WORK_ITEM", "item-a", first, WorkItemStatus.IN_PROGRESS, true),
        row("WORK_ITEM", "item-b", second, WorkItemStatus.IN_PROGRESS, false),
        row("WORK_ITEM", "item-c", third, WorkItemStatus.ARCHIVED, true),
        row("TASK_EXECUTION", "execution-a", null, null, false),
        row("INBOX", "inbox-a", null, null, false)));
    when(fixture.itemPolicy.resolvePermission(
            eq(fixture.context), eq(fixture.organizationId), eq(fixture.teamId), any()))
        .thenReturn(WorkItemTransitionPermissionResolver.unrestricted());

    List<WorkDeskItem> items = fixture.summarize().sections().get(0).items();

    // A natively owned WorkItem gets exactly the edges this member may execute now.
    assertEquals(
        List.of("提交评审", "标记阻塞", "取消工作项"),
        items.get(0).availableActions().stream().map(WorkItemAvailableTransition::label).toList());

    // An externally managed one, or an archived one, has nothing to offer — and neither does a row
    // that is not a WorkItem at all, which must keep the empty list the adapter handed it.
    for (WorkDeskItem item : items.subList(1, items.size())) {
      assertTrue(
          item.availableActions().isEmpty(),
          "no action may be offered for " + item.objectType() + " " + item.objectId());
    }
    assertTrue(items.get(3).transitionSubject().isEmpty());
    assertTrue(items.get(4).transitionSubject().isEmpty());

    // The budget gate: one read of the member's roles and grants for the whole page, wherever the
    // rows live. Three rows span three projects, so a per-row policy call would make this three, and
    // falling back to the permission check would make it worse still — that is the N+1 the WorkDesk
    // cannot afford.
    assertEquals(
        3,
        items.stream()
            .flatMap(item -> item.transitionSubject().stream())
            .map(WorkItemTransitionSubject::projectId)
            .distinct()
            .count());
    verify(fixture.itemPolicy, times(1))
        .resolvePermission(eq(fixture.context), eq(fixture.organizationId), eq(fixture.teamId), any());
    verify(fixture.itemPolicy, never())
        .hasPermission(any(), any(), any(), any(), any(), any());
  }

  private static WorkDeskItem row(
      String objectType,
      String objectId,
      WorkProjectId projectId,
      WorkItemStatus status,
      boolean nativeSource) {
    return new WorkDeskItem(
        objectType,
        objectId,
        Optional.ofNullable(projectId).map(WorkProjectId::toString),
        Optional.of("Row " + objectId),
        status == null ? "ACTIVE" : status.name(),
        Instant.parse("2026-09-13T09:00:00Z"),
        Optional.of("OWNER"),
        true,
        "HIGH",
        Optional.of(40),
        List.of(),
        status == null
            ? Optional.empty()
            : Optional.of(new WorkItemTransitionSubject(projectId, status, nativeSource)),
        "/work?workItem=" + objectId);
  }

  /** The M9b-A06 WorkItem-backed row: it names the WorkItem it speaks about. */
  private static WorkDeskItem workItemRow(WorkItemId workItemId, WorkItemStatus status) {
    return new WorkDeskItem(
        "WORK_ITEM",
        workItemId.toString(),
        Optional.empty(),
        Optional.of("Row " + workItemId),
        status.name(),
        Instant.parse("2026-09-13T09:00:00Z"),
        Optional.of("OWNER"),
        true,
        "HIGH",
        Optional.of(40),
        List.of(),
        Optional.of(new WorkItemTransitionSubject(WorkProjectId.generate(), status, true)),
        "/work?workItem=" + workItemId,
        Optional.of(workItemId.toString()),
        Optional.of("Title " + workItemId),
        Optional.empty(),
        Optional.empty());
  }

  /** A single-task item waiting on one named principal. */
  private static WorkItemExecutionSummary blockedSummary(WorkItemId id, PrincipalId blocking) {
    TaskId taskId = TaskId.generate();
    TaskExecutionId executionId = TaskExecutionId.generate();
    return new WorkItemExecutionSummary(
        id, 3L, WorkItemStatus.IN_PROGRESS, 1, 1, 0,
        Optional.of(taskId), Optional.of(executionId),
        Optional.of(TaskExecutionStatus.WAITING),
        false,
        List.of(WorkItemBlockedReason.waiting(
            TaskExecutionWaitReason.MANUAL, taskId, executionId, Optional.empty(),
            Optional.of(blocking))),
        Optional.empty(), Optional.empty(),
        3L, NOW);
  }

  /** The authorized member, the repository stub and the summary they hand back. */
  private static final class Fixture {
    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId principalId = PrincipalId.generate();
    private final TeamMember member = mock(TeamMember.class);
    private final TeamAccessContext context =
        new TeamAccessContext(mock(Principal.class), false);
    private final WorkItemAccessPolicy itemPolicy = mock(WorkItemAccessPolicy.class);
    private final WorkDeskRepository repository = mock(WorkDeskRepository.class);
    private final WorkItemSummaryRepository summaryRepository = mock(WorkItemSummaryRepository.class);
    private final WorkDeskQueryService service =
        new WorkDeskQueryService(
            repository,
            summaryRepository,
            new WorkDeskAccessPolicy(itemPolicy),
            itemPolicy,
            new WorkItemTransitionAvailabilityProjector(),
            () -> NOW);

    private Fixture() {
      when(member.id()).thenReturn(TeamMemberId.generate());
      when(member.userPrincipalId()).thenReturn(principalId);
      when(itemPolicy.requireVisibleTeamMember(any(), any(), any())).thenReturn(member);
      when(itemPolicy.resolvePermission(any(), any(), any(), any()))
          .thenReturn(WorkItemTransitionPermissionResolver.unrestricted());
      when(summaryRepository.summarize(any(), any(), any(), any(), any()))
          .thenReturn(Map.of());
    }

    private void summaryRepositoryAnswers(Map<WorkItemId, WorkItemExecutionSummary> answers) {
      when(summaryRepository.summarize(any(), any(), any(), any(), any()))
          .thenReturn(answers);
    }

    private WorkDeskSummary summarize() {
      return summarize(Optional.empty());
    }

    private WorkDeskSummary summarize(Optional<WorkProjectId> projectId) {
      return service.summarize(context, organizationId, teamId, projectId, Optional.empty(), true);
    }

    private WorkDeskSummary summary(List<WorkDeskItem> items) {
      WorkDeskSummary summary = new WorkDeskSummary(
          organizationId.toString(),
          teamId.toString(),
          Optional.empty(),
          Instant.parse("2026-09-13T00:00:00Z"),
          List.of(new WorkDeskSection("WORK_ITEM", "我的工作项", 4, items.size(), false, items)));
      when(repository.summarize(any(WorkDeskQuery.class))).thenReturn(summary);
      return summary;
    }
  }
}
