package io.crewscope.application.workitem;

import static io.crewscope.application.workitem.WorkItemCommandTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemCommandTestSupport.Fixture;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.RoleScopeType;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamRoleKey;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkItemKeyConflictException;
import io.crewscope.domain.workitem.WorkItemLabel;
import io.crewscope.domain.workitem.WorkItemPriority;
import io.crewscope.domain.workitem.WorkItemSource;
import io.crewscope.domain.workitem.WorkItemStatus;
import io.crewscope.domain.workitem.WorkItemType;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.event.WorkItemCreated;
import io.crewscope.domain.workspace.Workspace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkItemCommandServiceTest {

  @Test
  void contentEditingDoesNotReopenTerminalStatesAndArchivedIsReadOnly() {
    for (var status : List.of(WorkItemStatus.DONE, WorkItemStatus.CANCELLED, WorkItemStatus.ARCHIVED)) {
      Fixture fixture = new Fixture();
      WorkItem original = fixture.create(fixture.context("create"), fixture.createCommand("CRW-1", "Before")).result().orElseThrow();
      WorkItem terminal = WorkItem.reconstitute(original.id(), original.scope(), original.key(), original.title(), status, 0, original.audit());
      fixture.store.items.put(terminal.id(), terminal);
      var command = new UpdateWorkItemContentCommand(UpdateWorkItemContentCommand.Field.of("After"),
          UpdateWorkItemContentCommand.Field.absent(), UpdateWorkItemContentCommand.Field.absent(),
          UpdateWorkItemContentCommand.Field.absent(), UpdateWorkItemContentCommand.Field.absent(), 0);
      if (status == WorkItemStatus.ARCHIVED) {
        assertThrows(io.crewscope.domain.shared.error.InvalidStateTransitionException.class,
            () -> fixture.service.updateContent(fixture.context("edit"), fixture.initialization.team().id(), fixture.project.id(), terminal.id(), command));
        assertEquals(1, fixture.store.events.size());
      } else {
        var changed = fixture.service.updateContent(fixture.context("edit"), fixture.initialization.team().id(), fixture.project.id(), terminal.id(), command).result().orElseThrow();
        assertEquals(status, changed.status());
        assertEquals("After", changed.title());
      }
    }
  }

  @Test
  void suspendedMemberCannotEditContent() {
    Fixture fixture = new Fixture();
    var item = fixture.create(fixture.context("create"), fixture.createCommand("CRW-1", "Before")).result().orElseThrow();
    fixture.store.members = List.of(fixture.initialization.ownerMember().suspend(NOW));
    var command = new UpdateWorkItemContentCommand(UpdateWorkItemContentCommand.Field.of("After"),
        UpdateWorkItemContentCommand.Field.absent(), UpdateWorkItemContentCommand.Field.absent(),
        UpdateWorkItemContentCommand.Field.absent(), UpdateWorkItemContentCommand.Field.absent(), 0);
    assertThrows(PolicyDeniedException.class, () -> fixture.service.updateContent(fixture.context("edit"),
        fixture.initialization.team().id(), fixture.project.id(), item.id(), command));
    assertEquals(1, fixture.store.events.size());
  }

  @Test
  void allocatesKeysAfterReservationAndNeverReallocatesOnReplay() {
    Fixture fixture = new Fixture();
    var command = fixture.createCommand(null, "Automatic");
    var first = fixture.create(fixture.context("auto-one"), command);
    var second = fixture.create(fixture.context("auto-two"), command);
    assertEquals("CRW-1", first.result().orElseThrow().key().value());
    assertEquals("CRW-2", second.result().orElseThrow().key().value());
    assertEquals(first.receipt(), fixture.create(fixture.context("auto-one"), command).receipt());
    assertEquals(2, fixture.store.items.size());
    assertThrows(IdempotencyConflictException.class, () -> fixture.create(fixture.context("auto-one"),
        fixture.createCommand("CRW-1", "Automatic")));
  }

  @Test
  void editsOnlyContentWithPresenceVersionAndHistory() {
    Fixture fixture = new Fixture();
    WorkItem item = fixture.create(fixture.context("create-edit"), fixture.createCommand("CRW-1", "Original")).result().orElseThrow();
    var command = new UpdateWorkItemContentCommand(
        UpdateWorkItemContentCommand.Field.of("  Updated  "), UpdateWorkItemContentCommand.Field.of(null),
        UpdateWorkItemContentCommand.Field.absent(), UpdateWorkItemContentCommand.Field.of(Set.of()),
        UpdateWorkItemContentCommand.Field.of(null), item.version());
    var first = fixture.service.updateContent(fixture.context("edit-one"), fixture.initialization.team().id(),
        fixture.project.id(), item.id(), command);
    var changed = first.result().orElseThrow();
    assertEquals("Updated", changed.title());
    assertTrue(changed.description().isEmpty());
    assertTrue(changed.labels().isEmpty());
    assertTrue(changed.dueAt().isEmpty());
    assertEquals(item.type(), changed.type());
    assertEquals(item.key(), changed.key());
    assertEquals(item.status(), changed.status());
    assertEquals(item.priority(), changed.priority());
    assertEquals(item.version() + 1, changed.version());
    assertEquals("Original", item.title()); // Original snapshots are immutable.
    assertEquals("WORK_ITEM_CONTENT_UPDATED", fixture.store.events.get(1).eventType().value());
    assertEquals(first.receipt(), fixture.service.updateContent(fixture.context("edit-one"),
        fixture.initialization.team().id(), fixture.project.id(), item.id(), command).receipt());
    assertThrows(OptimisticLockConflictException.class, () -> fixture.service.updateContent(fixture.context("edit-two"),
        fixture.initialization.team().id(), fixture.project.id(), item.id(), command));
    assertEquals(2, fixture.store.events.size());
  }

  @Test
  void createsAllNativeFieldsAndReplaysTheOriginalReceipt() {
    Fixture fixture = new Fixture();
    CreateNativeWorkItemCommand command = fixture.createCommand("CRW-1", "Build API");
    TeamCommandContext context = fixture.context("create-item-1");

    CommandExecution<WorkItem> first = fixture.create(context, command);
    CommandExecution<WorkItem> replay = fixture.create(context, command);

    WorkItem item = first.result().orElseThrow();
    assertEquals(WorkItemType.FEATURE, item.type());
    assertEquals(Optional.of("Detailed plan"), item.description());
    assertEquals(WorkItemPriority.HIGH, item.priority());
    assertEquals(Set.of(new WorkItemLabel("backend")), item.labels());
    assertEquals(WorkItemSource.CREWSCOPE, item.source());
    assertEquals(WorkItemStatus.BACKLOG, item.status());
    assertEquals("WORK_ITEM_CREATED", fixture.store.events.get(0).eventType().value());
    ResponsibilityAssignment owner =
        fixture.store.findActiveOwner(fixture.organizationId, item.id()).orElseThrow();
    assertEquals(fixture.actor.id(), owner.actorPrincipalId());
    WorkItemCreated created =
        (WorkItemCreated) fixture.store.events.get(0).payload();
    assertEquals(Optional.of(owner.id().value()), created.initialOwnerAssignmentId());
    assertEquals(Optional.of(fixture.actor.id().value()), created.initialOwnerPrincipalId());
    assertEquals(1, fixture.store.outbox.size());
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.store.items.size());
    assertEquals(1, fixture.store.results.size());
    var durable = fixture.store.results.values().iterator().next();
    assertEquals(item.id().value(), durable.resourceId());
    assertEquals(fixture.actor.id(), durable.actorId());
    assertEquals(first.receipt(), durable.receipt());
  }

  @Test
  void rejectsChangedIdempotentContentAndAProjectDuplicateKey() {
    Fixture fixture = new Fixture();
    fixture.create(fixture.context("create-item-2"), fixture.createCommand("CRW-2", "First"));

    assertThrows(
        IdempotencyConflictException.class,
        () ->
            fixture.create(
                fixture.context("create-item-2"), fixture.createCommand("CRW-2", "Changed")));
    assertThrows(
        WorkItemKeyConflictException.class,
        () ->
            fixture.create(
                fixture.context("create-item-3"), fixture.createCommand("CRW-2", "Duplicate")));
    assertEquals(1, fixture.store.items.size());
  }

  @Test
  void acceptsMatchingProjectScopedWorkPermissionsButRejectsAnotherProjectScope() {
    Fixture fixture = new Fixture();
    fixture.useProjectRole(fixture.project.id());
    WorkItem item =
        fixture
            .create(
                fixture.context("project-scope-create"),
                fixture.createCommand("CRW-3", "Scoped"))
            .result()
            .orElseThrow();
    assertEquals("CRW-3", item.key().value());

    Fixture denied = new Fixture();
    denied.useProjectRole(WorkProjectId.generate());
    assertThrows(
        PolicyDeniedException.class,
        () ->
            denied.create(
                denied.context("wrong-project-scope"),
                denied.createCommand("CRW-4", "Denied")));
  }

  @Test
  void transitionsWithExpectedVersionAndReplaysWithoutAnotherMutation() {
    Fixture fixture = new Fixture();
    WorkItem item =
        fixture
            .create(
                fixture.context("transition-source"),
                fixture.createCommand("CRW-5", "Transition"))
            .result()
            .orElseThrow();
    TeamCommandContext context = fixture.context("transition-item-1");

    CommandExecution<WorkItem> first =
        fixture.transition(
            context, item.id(), new TransitionWorkItemCommand(WorkItemStatus.READY, 0));
    CommandExecution<WorkItem> replay =
        fixture.transition(
            context, item.id(), new TransitionWorkItemCommand(WorkItemStatus.READY, 0));

    WorkItem changed = first.result().orElseThrow();
    assertEquals(WorkItemStatus.READY, changed.status());
    assertEquals(1, changed.version());
    assertEquals("WORK_ITEM_STATUS_CHANGED", fixture.store.events.get(1).eventType().value());
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(WorkItemStatus.READY, fixture.store.items.get(item.id()).status());
  }

  @Test
  void rejectsStaleVersionAndInvalidStateTransition() {
    Fixture fixture = new Fixture();
    WorkItem item =
        fixture
            .create(
                fixture.context("invalid-transition-source"),
                fixture.createCommand("CRW-6", "Transition"))
            .result()
            .orElseThrow();

    OptimisticLockConflictException stale =
        assertThrows(
            OptimisticLockConflictException.class,
            () ->
                fixture.transition(
                    fixture.context("stale-transition"),
                    item.id(),
                    new TransitionWorkItemCommand(WorkItemStatus.READY, 2)));
    assertEquals("0", stale.error().details().get("actualVersion"));
    assertThrows(
        InvalidStateTransitionException.class,
        () ->
            fixture.transition(
                fixture.context("invalid-transition"),
                item.id(),
                new TransitionWorkItemCommand(WorkItemStatus.DONE, 0)));
    assertEquals(1, fixture.store.events.size());
  }

  @Test
  void rejectsExternalProjectionTransitionsAndSuspendedMemberships() {
    Fixture fixture = new Fixture();
    WorkItem external =
        WorkItem.createExternalProjection(
            WorkItemId.generate(),
            fixture.project,
            new WorkItemKey("CRW-7"),
            WorkItemType.BUG,
            "External",
            Optional.empty(),
            WorkItemPriority.MEDIUM,
            Set.of(),
            Optional.empty(),
            WorkItemSource.JIRA,
            "JIRA-7",
            fixture.actor,
            NOW);
    fixture.store.create(external);
    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.transition(
                fixture.context("external-transition"),
                external.id(),
                new TransitionWorkItemCommand(WorkItemStatus.READY, 0)));

    Fixture suspended = new Fixture();
    suspended.store.members = List.of(suspended.initialization.ownerMember().suspend(NOW));
    assertThrows(
        PolicyDeniedException.class,
        () ->
            suspended.create(
                suspended.context("suspended-create"),
                suspended.createCommand("CRW-8", "Suspended")));
  }
}
