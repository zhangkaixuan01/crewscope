package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.availability.TransitionBlockReason;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemTransitionCatalog;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves transition availability is derived from the domain catalog and shared access policy. */
class WorkItemTransitionAvailabilityQueryServiceTest {

  @Test
  void exposesOnlyDomainEdgesAndEnablesNativeTransitionsWithPermission() {
    Fixture fixture = new Fixture(WorkItemSource.CREWSCOPE, true);

    List<WorkItemAvailableTransition> result = fixture.service.list(fixture.query);

    assertEquals(2, result.size());
    assertEquals("submit-ready", result.get(0).actionId());
    assertTrue(result.stream().allMatch(WorkItemAvailableTransition::enabled));
  }

  @Test
  void keepsTransitionsVisibleButDisabledForAnExternalProviderOwnedItem() {
    Fixture fixture = new Fixture(WorkItemSource.JIRA, true);

    List<WorkItemAvailableTransition> result = fixture.service.list(fixture.query);

    assertTrue(result.stream().allMatch(value -> !value.enabled()));
    assertTrue(result.stream().allMatch(value ->
        value.reason().orElseThrow() == TransitionBlockReason.EXTERNAL_PROVIDER_MANAGED));
  }

  @Test
  void returnsPermissionReasonWithoutChangingTheCatalog() {
    Fixture fixture = new Fixture(WorkItemSource.CREWSCOPE, false);

    List<WorkItemAvailableTransition> result = fixture.service.list(fixture.query);

    assertEquals(2, result.size());
    assertTrue(result.stream().allMatch(value ->
        value.reason().orElseThrow() == TransitionBlockReason.PERMISSION_DENIED));
  }

  @Test
  void projectsNothingAtAllForAnArchivedItemSoTheArchivedReasonIsUnreachable() {
    Fixture fixture = new Fixture(WorkItemSource.CREWSCOPE, true, WorkItemStatus.ARCHIVED);

    List<WorkItemAvailableTransition> result = fixture.service.list(fixture.query);

    // ARCHIVED is a terminal state with no outgoing edge, so the catalog is empty before any
    // verdict is reached. The projector still checks archival first, because an archived item that
    // later gained an edge must not be offered as executable.
    assertTrue(result.isEmpty(), "an archived item has no edge to disable");
    assertTrue(WorkItemTransitionCatalog.from(WorkItemStatus.ARCHIVED).isEmpty());
  }

  private static final class Fixture {
    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final WorkProjectId projectId = WorkProjectId.generate();
    private final WorkItemId workItemId = WorkItemId.generate();
    private final TeamAccessContext context = new TeamAccessContext(mock(Principal.class), false);
    private final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    private final WorkItem item = mock(WorkItem.class);
    private final WorkItemTransitionAvailabilityQuery query =
        new WorkItemTransitionAvailabilityQuery(context, organizationId, teamId, projectId, workItemId);
    private final WorkItemTransitionAvailabilityQueryService service;

    private Fixture(WorkItemSource source, boolean permission) {
      this(source, permission, WorkItemStatus.BACKLOG);
    }

    private Fixture(WorkItemSource source, boolean permission, WorkItemStatus status) {
      when(item.status()).thenReturn(status);
      when(item.source()).thenReturn(source);
      when(accessPolicy.requireVisibleWorkItem(context, organizationId, teamId, projectId, workItemId))
          .thenReturn(item);
      when(accessPolicy.hasPermission(any(), any(), any(), any(), any(), any())).thenReturn(permission);
      service = new WorkItemTransitionAvailabilityQueryService(
          accessPolicy,
          new WorkItemTransitionAvailabilityProjector(),
          () -> UtcTimestamp.parse("2026-09-13T00:00:00Z"));
    }
  }
}
