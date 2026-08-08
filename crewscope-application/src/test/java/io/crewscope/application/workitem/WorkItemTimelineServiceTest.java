package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Proves WorkItem visibility is resolved before timeline rows are requested. */
class WorkItemTimelineServiceTest {

  @Test
  void passesTheAuthorizedScopeVisibleTypesCursorAndLimitToTheRepository() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    WorkItemTimelineEvent event = event(fixture);
    WorkItemTimelineCursor cursor = event.cursor();
    AtomicReference<WorkItemTimelineQuery> captured = new AtomicReference<>();
    WorkItemTimelineService service =
        service(
            fixture,
            query -> {
              captured.set(query);
              return new WorkItemTimelinePage(List.of(event), Optional.of(cursor));
            });

    WorkItemTimelinePage page =
        service.list(
            fixture.access(),
            fixture.organizationId,
            fixture.initialization.team().id(),
            fixture.project.id(),
            fixture.item.id(),
            Optional.of(cursor),
            25);

    assertEquals(List.of(event), page.items());
    assertEquals(fixture.item.scope().workspaceId(), captured.get().workspaceId());
    assertEquals(fixture.item.id(), captured.get().workItemId());
    assertEquals(Optional.of(cursor), captured.get().cursor());
    assertEquals(25, captured.get().limit());
    assertEquals(
        10,
        captured.get().visibleEventTypes().size(),
        "Only the reviewed M1 business-event surface is visible");
  }

  @Test
  void rejectsSuspendedMembershipAndMismatchedUrlScopesBeforeReadingEvents() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    AtomicInteger repositoryCalls = new AtomicInteger();
    WorkItemTimelineService service =
        service(
            fixture,
            query -> {
              repositoryCalls.incrementAndGet();
              return new WorkItemTimelinePage(List.of(), Optional.empty());
            });
    fixture.members =
        List.of(
            fixture.initialization.ownerMember().suspend(
                UtcTimestamp.parse("2026-08-08T09:01:00Z")));

    assertThrows(
        PolicyDeniedException.class,
        () ->
            service.list(
                fixture.access(),
                fixture.organizationId,
                fixture.initialization.team().id(),
                fixture.project.id(),
                fixture.item.id(),
                Optional.empty(),
                50));
    assertEquals(0, repositoryCalls.get());

    WorkItemCollaborationTestFixture scoped = new WorkItemCollaborationTestFixture();
    WorkItemTimelineService scopedService =
        service(scoped, query -> new WorkItemTimelinePage(List.of(), Optional.empty()));
    assertThrows(
        AggregateNotFoundException.class,
        () ->
            scopedService.list(
                scoped.access(),
                scoped.organizationId,
                TeamId.generate(),
                scoped.project.id(),
                scoped.item.id(),
                Optional.empty(),
                50));
    assertThrows(
        AggregateNotFoundException.class,
        () ->
            scopedService.list(
                scoped.access(),
                scoped.organizationId,
                scoped.initialization.team().id(),
                WorkProjectId.generate(),
                scoped.item.id(),
                Optional.empty(),
                50));
  }

  private static WorkItemTimelineService service(
      WorkItemCollaborationTestFixture fixture, WorkItemTimelineRepository repository) {
    return new WorkItemTimelineService(repository, fixture.accessPolicy(), fixture);
  }

  private static WorkItemTimelineEvent event(WorkItemCollaborationTestFixture fixture) {
    UUID eventId = UUID.randomUUID();
    return new WorkItemTimelineEvent(
        eventId,
        Optional.of(eventId),
        eventId,
        WorkItemTimelineSource.DOMAIN_EVENT,
        "WORK_ITEM_CREATED",
        "1",
        "WORK_ITEM",
        fixture.item.id().value(),
        Optional.of(0L),
        EventActorType.USER,
        Optional.of(fixture.actor.id()),
        Optional.of(fixture.actor.displayName()),
        UUID.randomUUID(),
        Optional.empty(),
        fixture.NOW,
        "SUCCEEDED",
        "{\"itemKey\":\"CRW-1\"}");
  }
}
