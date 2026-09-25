package io.crewscope.application.responsibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.identity.PrincipalProvisioningResult;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.responsibility.ActiveOwnerExpectation;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentId;
import io.crewscope.domain.responsibility.ResponsibilityAssignmentStatus;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.event.ResponsibilityHandoverCreated;
import io.crewscope.domain.responsibility.handover.HandoverItemState;
import io.crewscope.domain.responsibility.handover.HandoverJobStatus;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItem;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverItemId;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJob;
import io.crewscope.domain.responsibility.handover.ResponsibilityHandoverJobId;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.TeamStatus;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * ADR-038 §1 handover semantics: permissions, source pinning, command replay routing, per-item
 * DONE/CONFLICT/DENIED outcomes and resumability of interrupted runs.
 */
class ResponsibilityHandoverApplicationServiceTest {

  private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-25T08:00:00Z");

  @Test
  void previewFiltersByRoleUnderMemberManage() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 2);
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 3);
    fixture.addAssignment(ResponsibilityRole.OWNER, 1);

    List<ResponsibilityAssignment> executors =
        fixture.service.preview(
            fixture.context("preview-query"), fixture.teamId, fixture.sourceMember.id(),
            Optional.of(ResponsibilityRole.EXECUTOR));
    assertEquals(2, executors.size());
    assertTrue(
        executors.stream().allMatch(value -> value.role() == ResponsibilityRole.EXECUTOR));

    doThrow(new PolicyDeniedException("manage Team members"))
        .when(fixture.accessPolicy)
        .requireTeamPermission(any(), any(), any(), any(), any(), any());
    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.preview(
                fixture.context("preview-denied"), fixture.teamId, fixture.sourceMember.id(),
                Optional.of(ResponsibilityRole.EXECUTOR)));
  }

  @Test
  void createJobRequiresMemberManage() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);
    doThrow(new PolicyDeniedException("manage Team members"))
        .when(fixture.accessPolicy)
        .requireTeamPermission(any(), any(), any(), any(), any(), any());

    assertThrows(
        PolicyDeniedException.class,
        () -> fixture.createJob(ResponsibilityRole.EXECUTOR, "denied-key"));
    assertTrue(fixture.repository.jobs.isEmpty());
  }

  @Test
  void createJobRejectsEmptyRoleSet() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.OWNER, 1);

    DomainValidationException empty =
        assertThrows(
            DomainValidationException.class,
            () -> fixture.createJob(ResponsibilityRole.EXECUTOR, "empty-role-key"));
    assertEquals("responsibilityHandover.items", empty.error().details().get("field"));
  }

  @Test
  void createJobRejectsMoreThan100Items() {
    Fixture fixture = new Fixture();
    for (int index = 0; index < 101; index++) {
      fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);
    }

    DomainValidationException overflow =
        assertThrows(
            DomainValidationException.class,
            () -> fixture.createJob(ResponsibilityRole.EXECUTOR, "overflow-key"));
    assertEquals("responsibilityHandover.items", overflow.error().details().get("field"));
  }

  @Test
  void createJobPrechecksResponsibilityManagePerItem() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);
    doThrow(new PolicyDeniedException("manage this WorkItem's responsibilities"))
        .when(fixture.accessPolicy)
        .requirePermission(any(), any(), any(), any(), any(), any(), any(), any());

    assertThrows(
        PolicyDeniedException.class,
        () -> fixture.createJob(ResponsibilityRole.EXECUTOR, "precheck-key"));
    assertTrue(fixture.repository.jobs.isEmpty());
    assertTrue(fixture.repository.items.isEmpty());
  }

  @Test
  void createJobRejectsTargetsThatCannotParticipate() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);

    Principal disabled =
        fixture.principal("Disabled", PrincipalType.USER, PrincipalStatus.DISABLED);
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.createJob(
                fixture.context("disabled-target"), fixture.teamId, fixture.sourceMember.id(),
                disabled.id(), ResponsibilityRole.EXECUTOR));

    Principal agent = fixture.agentPrincipal("Agent");
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.createJob(
                fixture.context("agent-target"), fixture.teamId, fixture.sourceMember.id(),
                agent.id(), ResponsibilityRole.EXECUTOR));

    Principal outsider =
        fixture.principal("Outsider", PrincipalType.USER, PrincipalStatus.ACTIVE);
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.createJob(
                fixture.context("outsider-target"), fixture.teamId, fixture.sourceMember.id(),
                outsider.id(), ResponsibilityRole.EXECUTOR));
  }

  @Test
  void createJobPinsSnapshotsPublishesEventAndQueuesItems() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 3);
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 4);

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "created-key");

    assertFalse(created.replayed());
    ResponsibilityHandoverJob job = created.job();
    assertEquals(HandoverJobStatus.PENDING, job.status());
    assertEquals(fixture.teamId, job.teamId());
    assertEquals(1, job.sourceAuthorizationVersion());
    assertEquals("created-key", job.commandId());
    assertEquals(2, created.items().size());
    assertTrue(
        created.items().stream()
            .allMatch(
                item ->
                    item.isPending()
                        && item.scope().teamId().equals(fixture.teamId)
                        && (item.expectedAssignmentVersion() == 3
                            || item.expectedAssignmentVersion() == 4)));

    assertEquals(1, fixture.repository.events.size());
    DomainEventEnvelope<? extends DomainEvent> envelope = fixture.repository.events.get(0);
    assertEquals("RESPONSIBILITY_HANDOVER_CREATED", envelope.eventType().value());
    ResponsibilityHandoverCreated payload =
        (ResponsibilityHandoverCreated) envelope.payload();
    assertEquals(job.id().value(), payload.jobId());
    assertEquals(ResponsibilityRole.EXECUTOR.name(), payload.role());
    assertEquals(2, payload.itemCount());
    assertEquals(1, payload.sourceAuthorizationVersion());
    assertEquals(1, fixture.repository.outbox.size());
  }

  @Test
  void createJobReplayReturnsTheOriginalJob() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);

    ResponsibilityHandoverApplicationService.HandoverJobCreated first =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "replay-key");
    ResponsibilityHandoverApplicationService.HandoverJobCreated replay =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "replay-key");

    assertTrue(replay.replayed());
    assertEquals(first.job().id(), replay.job().id());
    assertEquals(first.items().size(), replay.items().size());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.repository.jobs.size());
    assertEquals(1, fixture.repository.events.size());
  }

  @Test
  void processJobMarksItemsDoneThroughOwnerReplacement() {
    Fixture fixture = new Fixture();
    ResponsibilityAssignment source = fixture.addAssignment(ResponsibilityRole.OWNER, 2);
    fixture.addAssignment(ResponsibilityRole.OWNER, 5);
    ResponsibilityAssignment newOwner = fixture.detachedAssignment(ResponsibilityRole.OWNER);
    fixture.stubOwnerReplacement(newOwner);

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.OWNER, "owner-key");
    ResponsibilityHandoverApplicationService.HandoverJobView view =
        fixture.service.processJob(
            fixture.context("owner-process"), fixture.teamId, created.job().id());

    assertEquals(HandoverJobStatus.COMPLETED, view.job().status());
    assertEquals(2, view.items().size());
    assertTrue(view.items().stream().allMatch(item -> item.state() == HandoverItemState.DONE));
    assertTrue(
        view.items().stream()
            .allMatch(item -> item.resultAssignmentId().orElseThrow().equals(newOwner.id())));

    ArgumentCaptor<TeamCommandContext> contextCaptor =
        ArgumentCaptor.forClass(TeamCommandContext.class);
    ArgumentCaptor<ReplaceOwnerCommand> commandCaptor =
        ArgumentCaptor.forClass(ReplaceOwnerCommand.class);
    verify(fixture.commands, org.mockito.Mockito.times(2))
        .replaceOwner(contextCaptor.capture(), any(), any(), any(), commandCaptor.capture());

    // The replayed command must carry the job-scoped idempotency key and the pinned expectation.
    ResponsibilityHandoverItem firstItem = view.items().get(0);
    TeamCommandContext replayContext = contextCaptor.getAllValues().get(0);
    assertEquals(
        "handover:" + created.job().id() + ":" + firstItem.id() + ":owner",
        replayContext.idempotencyKey().value());
    assertEquals(Optional.of(created.job().id().value()), replayContext.causationId());
    ReplaceOwnerCommand replayed = commandCaptor.getAllValues().get(0);
    assertEquals(fixture.targetUser.id(), replayed.actorPrincipalId());
    assertEquals(
        Optional.of(firstItem.assignmentId()),
        replayed.expectation().assignmentId());
    assertEquals(
        firstItem.expectedAssignmentVersion(), replayed.expectation().assignmentVersion());
  }

  @Test
  void processJobMarksConflictWhenTheSourceVersionMoved() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 4);

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "moved-key");
    // The source fact moves after the job was queued (someone released it concurrently).
    ResponsibilityAssignmentId movedId = created.items().get(0).assignmentId();
    ResponsibilityAssignment moved = fixture.repository.assignments.get(movedId);
    // Still active, but its version moved (a concurrent responsibility command committed).
    fixture.repository.assignments.put(
        movedId, fixture.copyAssignment(moved, moved.version() + 1));

    ResponsibilityHandoverApplicationService.HandoverJobView view =
        fixture.service.processJob(
            fixture.context("moved-process"), fixture.teamId, created.job().id());

    assertEquals(HandoverJobStatus.COMPLETED, view.job().status());
    assertEquals(HandoverItemState.CONFLICT, view.items().get(0).state());
    assertEquals(
        Optional.of("SOURCE_ASSIGNMENT_CHANGED"), view.items().get(0).errorCode());
    verify(fixture.commands, never()).release(any(), any(), any(), any(), any(), any());
    verify(fixture.commands, never()).assignExecutor(any(), any(), any(), any(), any());
  }

  @Test
  void processJobMarksDeniedWhenTheCommandPolicyFails() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);
    when(fixture.commands.release(any(), any(), any(), any(), any(), any()))
        .thenThrow(new PolicyDeniedException("manage this WorkItem's responsibilities"));

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "denied-process-key");
    ResponsibilityHandoverApplicationService.HandoverJobView view =
        fixture.service.processJob(
            fixture.context("denied-process"), fixture.teamId, created.job().id());

    assertEquals(HandoverItemState.DENIED, view.items().get(0).state());
    assertEquals(
        Optional.of("RESPONSIBILITY_MANAGE_DENIED"), view.items().get(0).errorCode());
    verify(fixture.commands, never()).assignExecutor(any(), any(), any(), any(), any());
  }

  @Test
  void processJobReleasesTheSourceBeforeAssigningTheExecutor() {
    Fixture fixture = new Fixture();
    ResponsibilityAssignment source = fixture.addAssignment(ResponsibilityRole.EXECUTOR, 2);
    ResponsibilityAssignment assigned = fixture.detachedAssignment(ResponsibilityRole.EXECUTOR);
    fixture.stubRelease(source);
    fixture.stubExecutorAssignment(assigned);

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "executor-key");
    ResponsibilityHandoverApplicationService.HandoverJobView view =
        fixture.service.processJob(
            fixture.context("executor-process"), fixture.teamId, created.job().id());

    assertEquals(HandoverItemState.DONE, view.items().get(0).state());
    assertEquals(Optional.of(assigned.id()), view.items().get(0).resultAssignmentId());
    assertEquals(HandoverJobStatus.COMPLETED, view.job().status());

    InOrder order = inOrder(fixture.commands);
    order
        .verify(fixture.commands)
        .release(any(), any(), any(), any(), any(), any());
    order
        .verify(fixture.commands)
        .assignExecutor(any(), any(), any(), any(), any());

    ArgumentCaptor<TeamCommandContext> contexts =
        ArgumentCaptor.forClass(TeamCommandContext.class);
    verify(fixture.commands)
        .release(contexts.capture(), any(), any(), any(), any(), any());
    assertEquals(
        "handover:" + created.job().id() + ":" + view.items().get(0).id() + ":release",
        contexts.getValue().idempotencyKey().value());
    ArgumentCaptor<ReleaseResponsibilityCommand> releaseCommand =
        ArgumentCaptor.forClass(ReleaseResponsibilityCommand.class);
    verify(fixture.commands)
        .release(any(), any(), any(), any(), any(), releaseCommand.capture());
    assertEquals(2, releaseCommand.getValue().expectedVersion());
  }

  @Test
  void processJobRoutesGateReviewersForUserTargets() {
    Fixture fixture = new Fixture();
    ResponsibilityAssignment source = fixture.addAssignment(ResponsibilityRole.REVIEWER, 2);
    ResponsibilityAssignment assigned = fixture.detachedAssignment(ResponsibilityRole.REVIEWER);
    fixture.stubRelease(source);
    when(fixture.commands.assignGateReviewer(any(), any(), any(), any(), any()))
        .thenReturn(
            CommandExecution.completed(
                new GateReviewerAssignment(assigned, io.crewscope.domain.responsibility
                    .ReviewerEligibilityDecision.strict()),
                receipt()));

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.REVIEWER, "gate-key");
    ResponsibilityHandoverApplicationService.HandoverJobView view =
        fixture.service.processJob(
            fixture.context("gate-process"), fixture.teamId, created.job().id());

    assertEquals(HandoverItemState.DONE, view.items().get(0).state());
    assertEquals(Optional.of(assigned.id()), view.items().get(0).resultAssignmentId());
    InOrder order = inOrder(fixture.commands);
    order.verify(fixture.commands).release(any(), any(), any(), any(), any(), any());
    order.verify(fixture.commands).assignGateReviewer(any(), any(), any(), any(), any());
    verify(fixture.commands, never())
        .assignAdvisoryReviewer(any(), any(), any(), any(), any());
  }

  @Test
  void processJobRoutesAdvisoryReviewersForAgentTargets() {
    Fixture fixture = new Fixture();
    ResponsibilityAssignment source = fixture.addAssignment(ResponsibilityRole.REVIEWER, 2);
    Principal agent = fixture.agentPrincipal("Agent");
    // Agent targets are rejected by createJob, so this job is reconstituted directly to prove
    // the routing contract the item replay must honour.
    ResponsibilityHandoverJob job =
        ResponsibilityHandoverJob.create(
            ResponsibilityHandoverJobId.generate(),
            ORGANIZATION_ID,
            fixture.teamId,
            fixture.sourceMember.id(),
            agent.id(),
            ResponsibilityRole.REVIEWER,
            "agent-job-key",
            fixture.owner.id(),
            1,
            NOW);
    ResponsibilityHandoverItem item =
        ResponsibilityHandoverItem.create(
            ResponsibilityHandoverItemId.generate(), job.id(), source, fixture.owner.id(), NOW);
    fixture.repository.jobs.put(job.id(), job);
    fixture.repository.items.add(item);

    ResponsibilityAssignment assigned = fixture.detachedAssignment(ResponsibilityRole.REVIEWER);
    fixture.stubRelease(source);
    fixture.stubAdvisoryAssignment(assigned);

    ResponsibilityHandoverApplicationService.HandoverJobView view =
        fixture.service.processJob(
            fixture.context("agent-process"), fixture.teamId, job.id());

    assertEquals(HandoverItemState.DONE, view.items().get(0).state());
    assertEquals(Optional.of(assigned.id()), view.items().get(0).resultAssignmentId());
    verify(fixture.commands).assignAdvisoryReviewer(any(), any(), any(), any(), any());
    verify(fixture.commands, never()).assignGateReviewer(any(), any(), any(), any(), any());
  }

  @Test
  void processJobSkipsSettledItemsAndNeverReplaysDone() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 2);
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 3);
    ResponsibilityAssignment assigned = fixture.detachedAssignment(ResponsibilityRole.EXECUTOR);
    fixture.stubRelease(fixture.repository.assignments.values().iterator().next());
    fixture.stubExecutorAssignment(assigned);

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "resume-key");
    // An earlier interrupted run settled the first item; only the second may be replayed.
    ResponsibilityHandoverItem settled =
        fixture.repository.items.get(0).markDone(fixture.owner.id(), assigned.id(), NOW);
    fixture.repository.updateItem(settled);
    long settledVersion = settled.version();

    ResponsibilityHandoverApplicationService.HandoverJobView view =
        fixture.service.processJob(
            fixture.context("resume-process"), fixture.teamId, created.job().id());

    assertEquals(HandoverJobStatus.COMPLETED, view.job().status());
    assertEquals(settledVersion, view.items().get(0).version());
    assertEquals(Optional.of(assigned.id()), settled.resultAssignmentId());
    verify(fixture.commands, org.mockito.Mockito.times(1))
        .assignExecutor(any(), any(), any(), any(), any());
  }

  @Test
  void processJobIsIdempotentForTerminalJobs() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);
    ResponsibilityAssignment assigned = fixture.detachedAssignment(ResponsibilityRole.EXECUTOR);
    fixture.stubRelease(fixture.repository.assignments.values().iterator().next());
    fixture.stubExecutorAssignment(assigned);

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "terminal-key");
    ResponsibilityHandoverApplicationService.HandoverJobView first =
        fixture.service.processJob(
            fixture.context("terminal-first"), fixture.teamId, created.job().id());
    ResponsibilityHandoverApplicationService.HandoverJobView second =
        fixture.service.processJob(
            fixture.context("terminal-second"), fixture.teamId, created.job().id());

    assertEquals(HandoverJobStatus.COMPLETED, second.job().status());
    assertEquals(first.job().version(), second.job().version());
    assertEquals(first.items().get(0).version(), second.items().get(0).version());
    verify(fixture.commands, org.mockito.Mockito.times(1))
        .release(any(), any(), any(), any(), any(), any());
  }

  @Test
  void cancelJobOnlyBeforeTerminalAndKeepsSettledItems() {
    Fixture fixture = new Fixture();
    fixture.addAssignment(ResponsibilityRole.EXECUTOR, 1);

    ResponsibilityHandoverApplicationService.HandoverJobCreated created =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "cancel-key");
    ResponsibilityHandoverApplicationService.HandoverJobView cancelled =
        fixture.service.cancelJob(
            fixture.context("cancel-run"), fixture.teamId, created.job().id());
    assertEquals(HandoverJobStatus.CANCELLED, cancelled.job().status());
    assertEquals(HandoverItemState.PENDING, cancelled.items().get(0).state());

    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.cancelJob(
                fixture.context("cancel-again"), fixture.teamId, created.job().id()));

    // Processing a cancelled job reports the current state without replaying anything.
    ResponsibilityHandoverApplicationService.HandoverJobView afterCancel =
        fixture.service.processJob(
            fixture.context("process-cancelled"), fixture.teamId, created.job().id());
    assertEquals(HandoverJobStatus.CANCELLED, afterCancel.job().status());
    assertEquals(HandoverItemState.PENDING, afterCancel.items().get(0).state());
    verify(fixture.commands, never()).release(any(), any(), any(), any(), any(), any());

    // A completed job can no longer be cancelled.
    ResponsibilityAssignment assigned = fixture.detachedAssignment(ResponsibilityRole.EXECUTOR);
    fixture.stubRelease(fixture.repository.assignments.values().iterator().next());
    fixture.stubExecutorAssignment(assigned);
    ResponsibilityHandoverApplicationService.HandoverJobCreated second =
        fixture.createJob(ResponsibilityRole.EXECUTOR, "completed-key");
    fixture.service.processJob(
        fixture.context("complete-second"), fixture.teamId, second.job().id());
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.cancelJob(
                fixture.context("cancel-completed"), fixture.teamId, second.job().id()));
  }

  private static CommandReceipt receipt() {
    return new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
  }

  private static final class Fixture {

    final InMemory repository = new InMemory();
    final ResponsibilityCommandService commands = mock(ResponsibilityCommandService.class);
    final WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
    final ResponsibilityHandoverApplicationService service;

    final Principal owner = principal("Owner", PrincipalType.USER, PrincipalStatus.ACTIVE);
    final Principal sourceUser = principal("Source", PrincipalType.USER, PrincipalStatus.ACTIVE);
    final Principal targetUser = principal("Target", PrincipalType.USER, PrincipalStatus.ACTIVE);
    final TeamId teamId;
    final WorkItemScope itemScope;
    final TeamMember sourceMember;
    final TeamMember targetMember;

    private Fixture() {
      teamId = TeamId.generate();
      repository.teams.put(
          teamId,
          Team.reconstitute(
              teamId,
              ORGANIZATION_ID,
              "Handover Team",
              TeamMemberId.generate(),
              WorkspaceId.generate(),
              TeamStatus.ACTIVE,
              0,
              AuditMetadata.createdBy(owner.id(), NOW)));
      sourceMember = member(sourceUser);
      targetMember = member(targetUser);
      itemScope =
          new WorkItemScope(
              ORGANIZATION_ID, teamId, WorkspaceId.generate(), WorkProjectId.generate());
      service =
          new ResponsibilityHandoverApplicationService(
              repository,
              repository,
              commands,
              accessPolicy,
              repository,
              repository,
              repository::findMembersByTeam,
              repository,
              repository,
              repository,
              repository,
              new DirectTransactions(),
              () -> NOW);
    }

    Principal principal(String name, PrincipalType type, PrincipalStatus status) {
      return principal(name, type, status, Optional.empty());
    }

    Principal agentPrincipal(String name) {
      return principal(name, PrincipalType.SPECIALIST_AGENT, PrincipalStatus.ACTIVE,
          Optional.of(owner.id()));
    }

    private Principal principal(
        String name, PrincipalType type, PrincipalStatus status, Optional<PrincipalId> owner) {
      Principal value =
          Principal.reconstitute(
              PrincipalId.generate(),
              PrincipalScope.organization(ORGANIZATION_ID),
              type,
              owner,
              name,
              Optional.empty(),
              PrincipalVisibility.ORGANIZATION,
              status,
              0,
              LifecycleMetadata.createdAt(NOW));
      repository.principals.put(value.id(), value);
      return value;
    }

    private TeamMember member(Principal user) {
      TeamMember value =
          TeamMember.join(
              TeamMemberId.generate(),
              new TeamScope(ORGANIZATION_ID, teamId),
              user,
              TeamJoinMethod.BOOTSTRAP,
              NOW);
      repository.members.put(value.id(), value);
      return value;
    }

    ResponsibilityAssignment addAssignment(ResponsibilityRole role, long version) {
      ResponsibilityAssignment value = assignment(role, version, itemScope);
      repository.assignments.put(value.id(), value);
      return value;
    }

    /** Builds an assignment that is not stored, for command stub return values. */
    ResponsibilityAssignment detachedAssignment(ResponsibilityRole role) {
      return assignment(
          role,
          9,
          new WorkItemScope(
              ORGANIZATION_ID, teamId, WorkspaceId.generate(), WorkProjectId.generate()));
    }

    private ResponsibilityAssignment assignment(
        ResponsibilityRole role, long version, WorkItemScope scope) {
      return ResponsibilityAssignment.reconstitute(
          ResponsibilityAssignmentId.generate(),
          scope,
          WorkItemId.generate(),
          role,
          sourceUser.id(),
          PrincipalType.USER,
          Optional.of(sourceMember.id()),
          ResponsibilityAssignmentStatus.ACTIVE,
          owner.id(),
          NOW,
          NOW,
          Optional.empty(),
          Optional.empty(),
          version,
          AuditMetadata.createdBy(owner.id(), NOW));
    }

    ResponsibilityAssignment copyAssignment(ResponsibilityAssignment value, long version) {
      return ResponsibilityAssignment.reconstitute(
          value.id(),
          value.scope(),
          value.workItemId(),
          value.role(),
          value.actorPrincipalId(),
          value.actorType(),
          value.actorMemberId(),
          ResponsibilityAssignmentStatus.ACTIVE,
          owner.id(),
          NOW,
          NOW,
          Optional.empty(),
          Optional.empty(),
          version,
          AuditMetadata.createdBy(owner.id(), NOW));
    }

    TeamCommandContext context(String key) {
      return new TeamCommandContext(
          new TeamAccessContext(owner, false),
          IdempotencyKey.from(key),
          UUID.randomUUID(),
          Optional.empty());
    }

    ResponsibilityHandoverApplicationService.HandoverJobCreated createJob(
        ResponsibilityRole role, String key) {
      return service.createJob(context(key), teamId, sourceMember.id(), targetUser.id(), role);
    }

    void stubOwnerReplacement(ResponsibilityAssignment newOwner) {
      when(commands.replaceOwner(any(), any(), any(), any(), any()))
          .thenReturn(
              CommandExecution.completed(
                  new OwnerAssignmentChange(Optional.empty(), newOwner), receipt()));
    }

    void stubRelease(ResponsibilityAssignment released) {
      when(commands.release(any(), any(), any(), any(), any(), any()))
          .thenReturn(CommandExecution.completed(released, receipt()));
    }

    void stubExecutorAssignment(ResponsibilityAssignment assigned) {
      when(commands.assignExecutor(any(), any(), any(), any(), any()))
          .thenReturn(CommandExecution.completed(assigned, receipt()));
    }

    void stubAdvisoryAssignment(ResponsibilityAssignment assigned) {
      when(commands.assignAdvisoryReviewer(any(), any(), any(), any(), any()))
          .thenReturn(CommandExecution.completed(assigned, receipt()));
    }
  }

  private static final class DirectTransactions implements TransactionExecutor {

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }

  /** In-memory ports mirroring the JPA adapter semantics the service relies on. */
  private static final class InMemory
      implements ResponsibilityHandoverRepository,
          ResponsibilityAssignmentRepository,
          TeamRepository,
          TeamMemberRepository,
          PrincipalRepository,
          DomainEventStore,
          OutboxRepository,
          CommandReceiptStore {

    final Map<TeamId, Team> teams = new LinkedHashMap<>();
    final Map<TeamMemberId, TeamMember> members = new LinkedHashMap<>();
    final Map<PrincipalId, Principal> principals = new LinkedHashMap<>();
    final Map<ResponsibilityAssignmentId, ResponsibilityAssignment> assignments =
        new LinkedHashMap<>();
    final Map<ResponsibilityHandoverJobId, ResponsibilityHandoverJob> jobs =
        new LinkedHashMap<>();
    final List<ResponsibilityHandoverItem> items = new ArrayList<>();
    final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
    final List<PendingOutboxEvent> outbox = new ArrayList<>();
    final Map<String, ReceiptEntry> receipts = new HashMap<>();

    @Override
    public ResponsibilityHandoverJob createJob(
        ResponsibilityHandoverJob job, List<ResponsibilityHandoverItem> queued) {
      jobs.put(job.id(), job);
      items.addAll(queued);
      return job;
    }

    @Override
    public Optional<ResponsibilityHandoverJob> lockJobById(
        OrganizationId organizationId, ResponsibilityHandoverJobId jobId) {
      return findJobById(organizationId, jobId);
    }

    @Override
    public Optional<ResponsibilityHandoverJob> findJobById(
        OrganizationId organizationId, ResponsibilityHandoverJobId jobId) {
      return Optional.ofNullable(jobs.get(jobId))
          .filter(job -> job.organizationId().equals(organizationId));
    }

    @Override
    public Optional<ResponsibilityHandoverJob> findJobByCommandId(
        OrganizationId organizationId, String commandId) {
      return jobs.values().stream()
          .filter(job -> job.organizationId().equals(organizationId))
          .filter(job -> job.commandId().equals(commandId))
          .findFirst();
    }

    @Override
    public ResponsibilityHandoverJob updateJob(ResponsibilityHandoverJob job) {
      jobs.put(job.id(), job);
      return job;
    }

    @Override
    public List<ResponsibilityHandoverItem> findItems(
        OrganizationId organizationId, ResponsibilityHandoverJobId jobId) {
      return items.stream()
          .filter(item -> item.organizationId().equals(organizationId))
          .filter(item -> item.jobId().equals(jobId))
          .toList();
    }

    @Override
    public Optional<ResponsibilityHandoverItem> lockItemById(
        OrganizationId organizationId, ResponsibilityHandoverItemId itemId) {
      return items.stream()
          .filter(item -> item.organizationId().equals(organizationId))
          .filter(item -> item.id().equals(itemId))
          .findFirst();
    }

    @Override
    public ResponsibilityHandoverItem updateItem(ResponsibilityHandoverItem value) {
      for (int index = 0; index < items.size(); index++) {
        if (items.get(index).id().equals(value.id())) {
          items.set(index, value);
          return value;
        }
      }
      items.add(value);
      return value;
    }

    @Override
    public void lockResponsibilityChain(OrganizationId organizationId, WorkItemId workItemId) {
      // no-op: the in-memory fake has no row locks
    }

    @Override
    public ResponsibilityAssignment create(ResponsibilityAssignment assignment) {
      assignments.put(assignment.id(), assignment);
      return assignment;
    }

    @Override
    public ResponsibilityAssignment update(ResponsibilityAssignment assignment) {
      assignments.put(assignment.id(), assignment);
      return assignment;
    }

    @Override
    public Optional<ResponsibilityAssignment> findById(
        OrganizationId organizationId, ResponsibilityAssignmentId id) {
      return Optional.ofNullable(assignments.get(id))
          .filter(assignment -> assignment.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<ResponsibilityAssignment> findActiveOwner(
        OrganizationId organizationId, WorkItemId workItemId) {
      return assignments.values().stream()
          .filter(assignment -> assignment.scope().organizationId().equals(organizationId))
          .filter(assignment -> assignment.workItemId().equals(workItemId))
          .filter(assignment -> assignment.role() == ResponsibilityRole.OWNER)
          .filter(ResponsibilityAssignment::isActive)
          .findFirst();
    }

    @Override
    public List<ResponsibilityAssignment> findActiveByWorkItem(
        OrganizationId organizationId, WorkItemId workItemId) {
      return assignments.values().stream()
          .filter(assignment -> assignment.scope().organizationId().equals(organizationId))
          .filter(assignment -> assignment.workItemId().equals(workItemId))
          .filter(ResponsibilityAssignment::isActive)
          .toList();
    }

    @Override
    public Optional<ResponsibilityAssignment> findActive(
        OrganizationId organizationId,
        WorkItemId workItemId,
        ResponsibilityRole role,
        PrincipalId actorPrincipalId) {
      return assignments.values().stream()
          .filter(assignment -> assignment.scope().organizationId().equals(organizationId))
          .filter(assignment -> assignment.workItemId().equals(workItemId))
          .filter(assignment -> assignment.role() == role)
          .filter(assignment -> assignment.actorPrincipalId().equals(actorPrincipalId))
          .filter(ResponsibilityAssignment::isActive)
          .findFirst();
    }

    @Override
    public List<ResponsibilityAssignment> findActiveByActorMember(
        OrganizationId organizationId, TeamId teamId, TeamMemberId memberId) {
      return assignments.values().stream()
          .filter(assignment -> assignment.scope().organizationId().equals(organizationId))
          .filter(assignment -> assignment.scope().teamId().equals(teamId))
          .filter(assignment -> assignment.actorMemberId().isPresent())
          .filter(assignment -> assignment.actorMemberId().orElseThrow().equals(memberId))
          .filter(ResponsibilityAssignment::isActive)
          .toList();
    }

    @Override
    public Team create(Team team) {
      teams.put(team.id(), team);
      return team;
    }

    @Override
    public Team update(Team team) {
      teams.put(team.id(), team);
      return team;
    }

    @Override
    public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
      return Optional.ofNullable(teams.get(id))
          .filter(team -> team.organizationId().equals(organizationId));
    }

    @Override
    public Optional<Team> lockById(OrganizationId organizationId, TeamId id) {
      return findById(organizationId, id);
    }

    @Override
    public List<Team> findActiveByMember(OrganizationId organizationId, PrincipalId principalId) {
      return teams.values().stream()
          .filter(team -> team.organizationId().equals(organizationId))
          .filter(
              team ->
                  members.values().stream()
                      .anyMatch(
                          member ->
                              member.scope().teamId().equals(team.id())
                                  && member.userPrincipalId().equals(principalId)
                                  && member.canParticipate()))
          .toList();
    }

    @Override
    public Optional<io.crewscope.domain.team.UninitializedTeam> findUninitializedById(
        OrganizationId organizationId, TeamId id) {
      return Optional.empty();
    }

    @Override
    public Optional<io.crewscope.domain.team.UninitializedTeam> lockUninitializedById(
        OrganizationId organizationId, TeamId id) {
      return Optional.empty();
    }

    @Override
    public TeamMember create(TeamMember member) {
      members.put(member.id(), member);
      return member;
    }

    @Override
    public TeamMember update(TeamMember member) {
      members.put(member.id(), member);
      return member;
    }

    @Override
    public Optional<TeamMember> findById(OrganizationId organizationId, TeamMemberId id) {
      return Optional.ofNullable(members.get(id))
          .filter(member -> member.scope().organizationId().equals(organizationId));
    }

    List<TeamMember> findMembersByTeam(OrganizationId organizationId, TeamId teamId) {
      return members.values().stream()
          .filter(member -> member.scope().organizationId().equals(organizationId))
          .filter(member -> member.scope().teamId().equals(teamId))
          .toList();
    }

    @Override
    public Optional<Principal> findById(OrganizationId organizationId, PrincipalId principalId) {
      return Optional.ofNullable(principals.get(principalId))
          .filter(principal -> principal.scope().organizationId().equals(organizationId));
    }

    @Override
    public Optional<Principal> findByExternalIdentity(
        OrganizationId organizationId, String provider, String subject) {
      return Optional.empty();
    }

    @Override
    public boolean organizationExists(OrganizationId organizationId) {
      return ORGANIZATION_ID.equals(organizationId);
    }

    @Override
    public PrincipalProvisioningResult provisionUser(Principal candidate) {
      principals.put(candidate.id(), candidate);
      return new PrincipalProvisioningResult(candidate, true);
    }

    @Override
    public void append(DomainEventEnvelope<? extends DomainEvent> event) {
      events.add(event);
    }

    @Override
    public void enqueue(PendingOutboxEvent event) {
      outbox.add(event);
    }

    @Override
    public CommandReservation reserve(CommandReservationRequest request) {
      String key = request.organizationId() + ":" + request.idempotencyKey().value();
      ReceiptEntry existing = receipts.get(key);
      if (existing == null) {
        receipts.put(key, new ReceiptEntry(request, null));
        return CommandReservation.newlyAcquired();
      }
      if (!existing.request().commandType().equals(request.commandType())
          || !existing.request().requestHash().equals(request.requestHash())) {
        throw new IdempotencyConflictException(
            request.idempotencyKey().value(),
            existing.request().requestHash().value(),
            request.requestHash().value());
      }
      return CommandReservation.replay(existing.receipt());
    }

    @Override
    public void complete(
        OrganizationId organizationId,
        IdempotencyKey idempotencyKey,
        CommandReceipt receipt,
        UtcTimestamp completedAt) {
      String key = organizationId() + ":" + idempotencyKey.value();
      receipts.put(key, new ReceiptEntry(receipts.get(key).request(), receipt));
    }

    @Override
    public void saveResult(CommandResult result) {
      // unused by the handover service
    }

    @Override
    public Optional<CommandResult> findResult(
        OrganizationId organizationId, IdempotencyKey key, PrincipalId actorId) {
      return Optional.empty();
    }

    private String organizationId() {
      return ORGANIZATION_ID.value().toString();
    }

    private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
  }
}
