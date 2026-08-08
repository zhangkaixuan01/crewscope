package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemCommentId;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceLinkId;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves membership-gated WorkItem pages and consistent collaboration snapshots. */
class WorkItemQueryServiceTest {

  @Test
  void passesProjectStatusCursorAndLimitToTheRepository() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    WorkItemQueryService service = service(fixture);
    WorkItemCursor cursor = new WorkItemCursor(fixture.item.audit().updatedAt(), fixture.item.id());

    WorkItemPage page =
        service.list(
            fixture.access(),
            fixture.organizationId,
            fixture.initialization.team().id(),
            fixture.project.id(),
            Optional.of(WorkItemStatus.BACKLOG),
            Optional.of(cursor),
            25);

    assertEquals(1, page.items().size());
    assertEquals(Optional.of(fixture.project.id()), fixture.lastQuery.projectId());
    assertEquals(Optional.of(WorkItemStatus.BACKLOG), fixture.lastQuery.status());
    assertEquals(Optional.of(cursor), fixture.lastQuery.cursor());
    assertEquals(25, fixture.lastQuery.limit());
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

  private static WorkItemQueryService service(WorkItemCollaborationTestFixture fixture) {
    return new WorkItemQueryService(
        fixture,
        fixture.commentRepository,
        fixture.linkRepository,
        fixture.accessPolicy(),
        fixture);
  }
}
