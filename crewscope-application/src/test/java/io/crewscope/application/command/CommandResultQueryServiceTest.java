package io.crewscope.application.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.identity.*;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.*;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProjectId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CommandResultQueryServiceTest {
  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-22T00:00:00Z");
  private final OrganizationId org = OrganizationId.generate();
  private final Principal actor = Principal.create(PrincipalId.generate(),
      PrincipalScope.organization(org), PrincipalType.USER, Optional.empty(), "Creator",
      Optional.empty(), PrincipalVisibility.ORGANIZATION, NOW);
  private final TeamAccessContext access = new TeamAccessContext(actor, false);
  private final IdempotencyKey key = new IdempotencyKey("recover-command-1");
  private final TeamId team = TeamId.generate();
  private final WorkProjectId project = WorkProjectId.generate();
  private final CommandResultStore store = mock(CommandResultStore.class);
  private final WorkItemAccessPolicy work = mock(WorkItemAccessPolicy.class);
  private final ConversationApplicationService conversations = mock(ConversationApplicationService.class);
  private final TeamMemberRepository members = mock(TeamMemberRepository.class);
  private final CommandResultQueryService service = new CommandResultQueryService(
      store, work, conversations, members, new TransactionExecutor() {
        public <T> T required(Supplier<T> operation) { return operation.get(); }
      });

  @Test void reauthorizesEveryResourceTypeAgainstCurrentVisibility() {
    for (var type : CommandResult.ResourceType.values()) {
      var result = result(type, actor.id());
      when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(result));
      when(members.findByTeamAndUserPrincipalId(org, team, actor.id()))
          .thenReturn(Optional.of(member(result.resourceId())));
      assertEquals(Optional.of(result), service.find(access, org, key));
      switch (type) {
        case WORK_PROJECT -> verify(work).requireVisibleProject(access, org, team, project);
        case WORK_ITEM -> verify(work).requireVisibleWorkItem(access, org, team, project,
            new WorkItemId(result.resourceId()));
        case CONVERSATION -> verify(conversations).get(access, org, team,
            new ConversationId(result.resourceId()));
        case TEAM_MEMBER -> verify(members).findByTeamAndUserPrincipalId(org, team, actor.id());
      }
    }
  }

  @Test void teamMemberResultStaysPrivateToTheNamedActiveMembership() {
    var foreignMember = result(CommandResult.ResourceType.TEAM_MEMBER, actor.id());
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(foreignMember));
    when(members.findByTeamAndUserPrincipalId(org, team, actor.id()))
        .thenReturn(Optional.of(member(UUID.randomUUID())));
    assertTrue(service.find(access, org, key).isEmpty());

    var departing = result(CommandResult.ResourceType.TEAM_MEMBER, actor.id());
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(departing));
    when(members.findByTeamAndUserPrincipalId(org, team, actor.id()))
        .thenReturn(Optional.of(member(departing.resourceId()).suspend(NOW)));
    assertTrue(service.find(access, org, key).isEmpty());
  }

  private TeamMember member(UUID memberId) {
    return TeamMember.join(
        new TeamMemberId(memberId), new TeamScope(org, team), actor,
        TeamJoinMethod.OIDC, NOW);
  }

  @Test void lostMembershipOrHiddenProjectIsIndistinguishableFromMissingResult() {
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(result(
        CommandResult.ResourceType.WORK_PROJECT, actor.id())));
    when(work.requireVisibleProject(access, org, team, project))
        .thenThrow(new PolicyDeniedException("read project"));
    assertTrue(service.find(access, org, key).isEmpty());
  }

  @Test void deletedResourceDoesNotLeakItsCoordinates() {
    var result = result(CommandResult.ResourceType.WORK_ITEM, actor.id());
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(result));
    when(work.requireVisibleWorkItem(access, org, team, project, new WorkItemId(result.resourceId())))
        .thenThrow(new AggregateNotFoundException("WorkItem", new WorkItemId(result.resourceId())));
    assertTrue(service.find(access, org, key).isEmpty());
  }

  @Test void privateConversationCannotBeRecoveredAfterAccessIsLost() {
    var result = result(CommandResult.ResourceType.CONVERSATION, actor.id());
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(result));
    when(conversations.get(access, org, team, new ConversationId(result.resourceId())))
        .thenThrow(new PolicyDeniedException("read conversation"));
    assertTrue(service.find(access, org, key).isEmpty());
  }

  @Test void administratorCannotReadAnotherCreatorsResultEvenWithFaultyAdapter() {
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(
        result(CommandResult.ResourceType.WORK_PROJECT, PrincipalId.generate())));
    assertTrue(service.find(new TeamAccessContext(actor, true), org, key).isEmpty());
    verifyNoInteractions(work, conversations);
  }

  @Test void crossOrganizationAndInactiveActorsDoNotReadTheStore() {
    assertTrue(service.find(access, OrganizationId.generate(), key).isEmpty());
    var disabled = actor.transitionTo(PrincipalStatus.DISABLED, NOW);
    assertTrue(service.find(new TeamAccessContext(disabled, false), org, key).isEmpty());
    verifyNoInteractions(store, work, conversations);
  }

  @Test void unknownAndLegacyKeysReturnNoResultWithoutSearchingByName() {
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.empty());
    assertTrue(service.find(access, org, key).isEmpty());
    verifyNoInteractions(work, conversations);
  }

  @Test void unexpectedFailuresAreNotDisguisedAsMissingResults() {
    when(store.findResult(org, key, actor.id())).thenReturn(Optional.of(
        result(CommandResult.ResourceType.WORK_PROJECT, actor.id())));
    when(work.requireVisibleProject(access, org, team, project))
        .thenThrow(new IllegalStateException("database unavailable"));
    assertThrows(IllegalStateException.class, () -> service.find(access, org, key));
  }

  private CommandResult result(CommandResult.ResourceType type, PrincipalId creator) {
    boolean projectScoped = type != CommandResult.ResourceType.CONVERSATION
        && type != CommandResult.ResourceType.TEAM_MEMBER;
    return new CommandResult(org, key, creator, "CREATE_TEST", team,
        projectScoped ? Optional.of(project) : Optional.empty(),
        type, type == CommandResult.ResourceType.WORK_PROJECT ? project.value() : UUID.randomUUID(),
        0, new CommandReceipt(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID()),
        NOW);
  }
}
