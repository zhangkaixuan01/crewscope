package io.crewscope.application.workitem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemComment;
import io.crewscope.domain.workitem.WorkItemResourceLink;
import io.crewscope.domain.workitem.WorkItemResourceType;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves authorized, idempotent and durable WorkItem collaboration commands. */
class WorkItemCollaborationServiceTest {

  @Test
  void createsCommentsAndResourceLinksWithEventsOutboxAndReceipts() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    WorkItemCollaborationService service = service(fixture);

    CommandExecution<WorkItemComment> comment =
        service.addComment(
            fixture.commandContext("comment-1"),
            fixture.initialization.team().id(),
            fixture.project.id(),
            fixture.item.id(),
            new AddWorkItemCommentCommand("  Review complete  "));
    CommandExecution<WorkItemResourceLink> link =
        service.linkResource(
            fixture.commandContext("link-1"),
            fixture.initialization.team().id(),
            fixture.project.id(),
            fixture.item.id(),
            new LinkWorkItemResourceCommand(
                WorkItemResourceType.PULL_REQUEST,
                " github:crewscope/crewscope-java#42 ",
                Optional.of(" PR 42 ")));

    assertEquals("Review complete", comment.result().orElseThrow().content());
    assertEquals("github:crewscope/crewscope-java#42", link.result().orElseThrow().resourceReference());
    assertEquals("WORK_ITEM_COMMENT_ADDED", fixture.events.get(0).eventType().value());
    assertEquals("WORK_ITEM_RESOURCE_LINKED", fixture.events.get(1).eventType().value());
    assertEquals(2, fixture.outbox.size());
    assertEquals(2, fixture.receipts.size());
  }

  @Test
  void replaysTheOriginalReceiptAndRejectsChangedContent() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    WorkItemCollaborationService service = service(fixture);
    TeamCommandContext context = fixture.commandContext("comment-replay");

    CommandExecution<WorkItemComment> first =
        service.addComment(
            context,
            fixture.initialization.team().id(),
            fixture.project.id(),
            fixture.item.id(),
            new AddWorkItemCommentCommand("Stable content"));
    CommandExecution<WorkItemComment> replay =
        service.addComment(
            context,
            fixture.initialization.team().id(),
            fixture.project.id(),
            fixture.item.id(),
            new AddWorkItemCommentCommand(" Stable content "));

    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.comments.size());
    assertEquals(1, fixture.events.size());
    assertThrows(
        IdempotencyConflictException.class,
        () ->
            service.addComment(
                context,
                fixture.initialization.team().id(),
                fixture.project.id(),
                fixture.item.id(),
                new AddWorkItemCommentCommand("Changed content")));
  }

  @Test
  void honorsOnlyTeamOrTargetProjectParticipationGrants() {
    WorkItemCollaborationTestFixture allowed = new WorkItemCollaborationTestFixture();
    allowed.useProjectRole(allowed.project.id());
    service(allowed)
        .addComment(
            allowed.commandContext("target-project"),
            allowed.initialization.team().id(),
            allowed.project.id(),
            allowed.item.id(),
            new AddWorkItemCommentCommand("Allowed"));
    assertEquals(1, allowed.comments.size());

    WorkItemCollaborationTestFixture denied = new WorkItemCollaborationTestFixture();
    denied.useProjectRole(WorkProjectId.generate());
    assertThrows(
        PolicyDeniedException.class,
        () ->
            service(denied)
                .linkResource(
                    denied.commandContext("other-project"),
                    denied.initialization.team().id(),
                    denied.project.id(),
                    denied.item.id(),
                    new LinkWorkItemResourceCommand(
                        WorkItemResourceType.REPOSITORY,
                        "github:crewscope/crewscope-java",
                        Optional.empty())));
    assertEquals(0, denied.links.size());
  }

  @Test
  void rejectsCollaborationOnArchivedWorkItems() {
    WorkItemCollaborationTestFixture fixture = new WorkItemCollaborationTestFixture();
    WorkItem archived =
        fixture.item
            .transitionTo(
                WorkItemStatus.CANCELLED,
                fixture.actor,
                UtcTimestamp.parse("2026-08-08T09:01:00Z"))
            .transitionTo(
                WorkItemStatus.ARCHIVED,
                fixture.actor,
                UtcTimestamp.parse("2026-08-08T09:02:00Z"));
    fixture.items.put(archived.id(), archived);

    assertThrows(
        DomainValidationException.class,
        () ->
            service(fixture)
                .addComment(
                    fixture.commandContext("archived-comment"),
                    fixture.initialization.team().id(),
                    fixture.project.id(),
                    fixture.item.id(),
                    new AddWorkItemCommentCommand("Too late")));
    assertEquals(0, fixture.comments.size());
    assertEquals(0, fixture.events.size());
  }

  private static WorkItemCollaborationService service(WorkItemCollaborationTestFixture fixture) {
    return new WorkItemCollaborationService(
        fixture.commentRepository,
        fixture.linkRepository,
        fixture.accessPolicy(),
        fixture,
        fixture,
        fixture,
        fixture,
        () -> WorkItemCollaborationTestFixture.NOW);
  }
}
