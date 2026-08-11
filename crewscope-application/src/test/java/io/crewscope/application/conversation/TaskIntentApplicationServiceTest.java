package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.execution.TaskIntentOutputCandidate;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.WorkspaceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Proves the M2-A05 current-fact, idempotency and no-side-effect confirmation boundaries. */
class TaskIntentApplicationServiceTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-11T04:00:00Z");

  @Test
  void commitsOneStableReadyIntentBeforeRunFinishedAndDeduplicatesTheCandidate() {
    Fixture fixture = new Fixture();

    TaskIntent first = fixture.commitCandidate();
    TaskIntent replay = fixture.commitCandidate();

    assertSame(first, replay);
    assertEquals(TaskIntentStatus.READY, first.status());
    assertEquals(1, first.version());
    assertEquals(1, fixture.taskIntents.creates);
    assertEquals(1, fixture.taskIntents.updates);
    assertEquals(List.of("TASK_INTENT_PROPOSED"), fixture.eventTypes());
    assertEquals(1, fixture.conversationEventCount);
    assertEquals(1, fixture.outboxCount);

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.commitAgentProposal(
                new TaskIntentOutputCandidate(
                    fixture.invocationId,
                    fixture.segmentId,
                    fixture.conversation.conversation().id(),
                    fixture.output("Changed replay", List.of("Must not replace the fact")),
                    NOW),
                fixture.platformContext(),
                Optional.empty()));
  }

  @Test
  void rechecksTheStableCandidateAfterAcquiringTheConversationLock() {
    Fixture fixture = new Fixture();
    TaskIntent committed = fixture.commitCandidate();
    int eventsBefore = fixture.events.size();
    fixture.taskIntents.hideNextFind = true;

    TaskIntent replay = fixture.commitCandidate();

    assertSame(committed, replay);
    assertEquals(1, fixture.taskIntents.creates);
    assertEquals(1, fixture.taskIntents.updates);
    assertEquals(eventsBefore, fixture.events.size());
  }

  @Test
  void fullyRevisesAgainstCurrentFactsAndReturnsTheProposalToReady() {
    Fixture fixture = new Fixture();
    TaskIntent original = fixture.commitCandidate();
    TaskIntentV1 replacement =
        fixture.output("Ship the revised workflow", List.of("Revision is visible"));

    var execution =
        fixture.service.revise(
            fixture.commandContext(fixture.owner, "task-intent-revise-1"),
            fixture.team.team().id(),
            fixture.target(original),
            new ReviseTaskIntentCommand(replacement),
            original.version());

    TaskIntent revised = execution.result().orElseThrow();
    assertEquals(TaskIntentStatus.READY, revised.status());
    assertEquals(2, revised.proposalRevision());
    assertEquals(3, revised.version());
    assertEquals("Ship the revised workflow", revised.proposal().objective());
    assertEquals(List.of("TASK_INTENT_PROPOSED", "TASK_INTENT_REVISED"), fixture.eventTypes());
    assertEquals(revised.version(), execution.receipt().committedVersion());
  }

  @Test
  void confirmationPreviewRevalidatesFactsWithoutPersistingOrPublishing() {
    Fixture fixture = new Fixture();
    TaskIntent ready = fixture.commitCandidate();
    int updatesBefore = fixture.taskIntents.updates;
    int eventsBefore = fixture.events.size();

    TaskIntentConfirmationPreview preview =
        fixture.service.previewConfirmation(
            fixture.access(fixture.owner),
            fixture.organizationId,
            fixture.team.team().id(),
            fixture.target(ready),
            ready.version());

    assertSame(ready, preview.taskIntent());
    assertEquals(fixture.owner.id(), preview.confirmingPrincipalId());
    assertEquals(updatesBefore, fixture.taskIntents.updates);
    assertEquals(eventsBefore, fixture.events.size());

    assertThrows(
        OptimisticLockConflictException.class,
        () ->
            fixture.service.previewConfirmation(
                fixture.access(fixture.owner),
                fixture.organizationId,
                fixture.team.team().id(),
                fixture.target(ready),
                ready.version() + 1));

    fixture.currentProject = fixture.project.archive(fixture.owner, NOW);
    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.previewConfirmation(
                fixture.access(fixture.owner),
                fixture.organizationId,
                fixture.team.team().id(),
                fixture.target(ready),
                ready.version()));
  }

  @Test
  void onlyTheProposedOwnerCanRejectAndTheSameCommandReplaysItsReceipt() {
    Fixture fixture = new Fixture();
    TaskIntent ready = fixture.commitCandidate();

    assertThrows(
        PolicyDeniedException.class,
        () ->
            fixture.service.reject(
                fixture.commandContext(fixture.reviewer, "task-intent-reject-other"),
                fixture.team.team().id(),
                fixture.target(ready),
                new RejectTaskIntentCommand("Not mine"),
                ready.version()));

    TeamCommandContext context =
        fixture.commandContext(fixture.owner, "task-intent-reject-owner");
    var first =
        fixture.service.reject(
            context,
            fixture.team.team().id(),
            fixture.target(ready),
            new RejectTaskIntentCommand("Wrong target"),
            ready.version());
    var replay =
        fixture.service.reject(
            context,
            fixture.team.team().id(),
            fixture.target(ready),
            new RejectTaskIntentCommand("Wrong target"),
            ready.version());

    assertEquals(TaskIntentStatus.REJECTED, first.result().orElseThrow().status());
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.eventTypes().stream().filter("TASK_INTENT_REJECTED"::equals).count());
  }

  @Test
  void rejectsUnknownSchemaAtTheApplicationBoundary() {
    Fixture fixture = new Fixture();
    TaskIntent ready = fixture.commitCandidate();
    TaskIntentV1 unknownSchema =
        new TaskIntentV1(
            "2",
            "Changed",
            List.of("Changed"),
            fixture.project.id().toString(),
            fixture.team.ownerMember().id().toString(),
            fixture.agent.id().toString(),
            fixture.reviewerMember.id().toString());

    assertThrows(
        DomainValidationException.class,
        () ->
            fixture.service.revise(
                fixture.commandContext(fixture.owner, "task-intent-revise-schema"),
                fixture.team.team().id(),
                fixture.target(ready),
                new ReviseTaskIntentCommand(unknownSchema),
                ready.version()));
  }

  private static final class Fixture {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal owner = activeUser(organizationId, "Owner");
    private final Principal reviewer = activeUser(organizationId, "Reviewer");
    private final TeamInitialization team = TeamInitialization.create(owner, "CrewScope", NOW);
    private final TeamMember reviewerMember =
        team.team().joinMember(TeamMemberId.generate(), reviewer, TeamJoinMethod.OIDC, NOW);
    private final PersonalConversationInitialization conversation =
        PersonalConversationInitialization.start(
            ConversationId.generate(),
            team.defaultWorkspace(),
            team.ownerMember(),
            owner,
            team.ownerPersonalAgent(),
            "Plan M2-A05",
            ConversationVisibility.PRIVATE,
            NOW);
    private final Principal agent = team.ownerPersonalAgent().agentPrincipal();
    private final WorkProject project =
        WorkProject.create(
            WorkProjectId.generate(),
            new WorkProjectKey("CRW"),
            "CrewScope",
            team.team(),
            team.defaultWorkspace(),
            owner,
            NOW);
    private WorkProject currentProject = project;
    private final RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
    private final UUID segmentId = UUID.randomUUID();
    private final InMemoryTaskIntentRepository taskIntents = new InMemoryTaskIntentRepository();
    private final InMemoryReceiptStore receipts = new InMemoryReceiptStore();
    private final List<DomainEventEnvelope<?>> events = new ArrayList<>();
    private int conversationEventCount;
    private int outboxCount;
    private final TaskIntentApplicationService service;

    private Fixture() {
      ConversationApplicationService conversationService = mock(ConversationApplicationService.class);
      when(conversationService.get(any(), any(), any(), any()))
          .thenReturn(
              new ConversationDetails(
                  conversation.conversation(),
                  List.of(conversation.ownerParticipant(), conversation.agentParticipant())));
      ConversationRepository conversations = mock(ConversationRepository.class);
      when(conversations.lockById(organizationId, conversation.conversation().id()))
          .thenReturn(Optional.of(conversation.conversation()));
      ConversationParticipantRepository participants =
          mock(ConversationParticipantRepository.class);
      when(participants.findById(organizationId, conversation.agentParticipant().id()))
          .thenReturn(Optional.of(conversation.agentParticipant()));
      WorkProjectRepository projects = mock(WorkProjectRepository.class);
      when(projects.findById(any(), any())).thenAnswer(ignored -> Optional.of(currentProject));
      TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
      when(memberships.findByTeam(organizationId, team.team().id()))
          .thenReturn(List.of(team.ownerMember(), reviewerMember));
      PrincipalRepository principals = mock(PrincipalRepository.class);
      Map<Object, Principal> principalById =
          Map.of(owner.id(), owner, reviewer.id(), reviewer, agent.id(), agent);
      when(principals.findById(any(), any()))
          .thenAnswer(invocation -> Optional.ofNullable(principalById.get(invocation.getArgument(1))));
      DomainEventStore domainEvents = mock(DomainEventStore.class);
      doAnswer(
              invocation -> {
                events.add(invocation.getArgument(0));
                return null;
              })
          .when(domainEvents)
          .append(any());
      ConversationEventRepository conversationEvents = mock(ConversationEventRepository.class);
      doAnswer(
              invocation -> {
                conversationEventCount++;
                return null;
              })
          .when(conversationEvents)
          .append(any(), any());
      OutboxRepository outbox = mock(OutboxRepository.class);
      doAnswer(
              invocation -> {
                outboxCount++;
                return null;
              })
          .when(outbox)
          .enqueue(any());
      service =
          new TaskIntentApplicationService(
              conversationService,
              conversations,
              participants,
              conversationEvents,
              taskIntents,
              projects,
              memberships,
              principals,
              domainEvents,
              outbox,
              receipts,
              new DirectTransactionExecutor(),
              TimeProvider.from(
                  Clock.fixed(Instant.parse("2026-08-11T04:00:00Z"), ZoneOffset.UTC)));
    }

    private TaskIntent commitCandidate() {
      return service.commitAgentProposal(
          new TaskIntentOutputCandidate(
              invocationId,
              segmentId,
              conversation.conversation().id(),
              output("Ship TaskIntent review", List.of("Owner can review")),
              NOW),
          platformContext(),
          Optional.empty());
    }

    private TaskIntentV1 output(String objective, List<String> criteria) {
      return new TaskIntentV1(
          TaskIntentV1.SCHEMA_VERSION,
          objective,
          criteria,
          project.id().toString(),
          team.ownerMember().id().toString(),
          agent.id().toString(),
          reviewerMember.id().toString());
    }

    private PlatformExecutionContext platformContext() {
      AgentRuntimeSessionId runtimeSessionId =
          AgentRuntimeSessionId.forPersonalConversation(
              conversation.conversation().id(), team.ownerMember().id(), agent.id());
      return new PlatformExecutionContext(
          conversation.conversation().scope(),
          WorkspaceType.TEAM,
          owner.id(),
          team.ownerMember().id(),
          Set.of(),
          Set.of(),
          agent.id(),
          AgentProfileId.generate(),
          0,
          conversation.conversation().id(),
          conversation.conversation().visibility(),
          conversation.ownerParticipant().id(),
          conversation.agentParticipant().id(),
          runtimeSessionId,
          AgentScopeSessionKey.forPersonalConversation(
              organizationId,
              team.ownerMember().id(),
              agent.id(),
              conversation.conversation().id(),
              runtimeSessionId),
          invocationId,
          UUID.randomUUID(),
          Set.<ProviderType>of(),
          Map.of());
    }

    private TeamAccessContext access(Principal actor) {
      return new TeamAccessContext(actor, false);
    }

    private TeamCommandContext commandContext(Principal actor, String key) {
      return new TeamCommandContext(
          access(actor), new IdempotencyKey(key), UUID.randomUUID(), Optional.empty());
    }

    private ConversationIdAndTaskIntentId target(TaskIntent intent) {
      return new ConversationIdAndTaskIntentId(conversation.conversation().id(), intent.id());
    }

    private List<String> eventTypes() {
      return events.stream().map(event -> event.eventType().value()).toList();
    }
  }

  private static final class InMemoryTaskIntentRepository implements TaskIntentRepository {

    private TaskIntent value;
    private int creates;
    private int updates;
    private boolean hideNextFind;

    @Override
    public TaskIntent create(TaskIntent taskIntent) {
      if (value != null) {
        throw new IllegalStateException("duplicate TaskIntent");
      }
      value = taskIntent;
      creates++;
      return value;
    }

    @Override
    public TaskIntent update(TaskIntent taskIntent) {
      if (value == null || taskIntent.version() != value.version() + 1) {
        throw new IllegalStateException("TaskIntent update must advance one version");
      }
      value = taskIntent;
      updates++;
      return value;
    }

    @Override
    public TaskIntent confirm(TaskIntent taskIntent, WorkItemId confirmedWorkItemId) {
      throw new UnsupportedOperationException("M2-A07 owns confirmation persistence");
    }

    @Override
    public Optional<TaskIntent> findById(OrganizationId organizationId, TaskIntentId id) {
      if (hideNextFind) {
        hideNextFind = false;
        return Optional.empty();
      }
      return matches(organizationId, id);
    }

    @Override
    public Optional<TaskIntent> lockById(OrganizationId organizationId, TaskIntentId id) {
      return matches(organizationId, id);
    }

    @Override
    public Optional<WorkItemId> findConfirmedWorkItemId(
        OrganizationId organizationId, TaskIntentId id) {
      return Optional.empty();
    }

    private Optional<TaskIntent> matches(OrganizationId organizationId, TaskIntentId id) {
      return Optional.ofNullable(value)
          .filter(intent -> intent.scope().organizationId().equals(organizationId))
          .filter(intent -> intent.id().equals(id));
    }
  }

  private static final class InMemoryReceiptStore implements CommandReceiptStore {

    private final Map<String, CommandReservationRequest> reservations = new LinkedHashMap<>();
    private final Map<String, CommandReceipt> completed = new LinkedHashMap<>();

    @Override
    public CommandReservation reserve(CommandReservationRequest request) {
      String key = request.idempotencyKey().value();
      CommandReservationRequest prior = reservations.putIfAbsent(key, request);
      if (prior == null) {
        return CommandReservation.newlyAcquired();
      }
      if (!prior.commandType().equals(request.commandType())
          || !prior.requestHash().equals(request.requestHash())) {
        throw new IllegalStateException("idempotency conflict");
      }
      return CommandReservation.replay(completed.get(key));
    }

    @Override
    public void complete(
        OrganizationId organizationId,
        IdempotencyKey idempotencyKey,
        CommandReceipt receipt,
        UtcTimestamp completedAt) {
      completed.put(idempotencyKey.value(), receipt);
    }
  }

  private static final class DirectTransactionExecutor implements TransactionExecutor {

    @Override
    public <T> T required(Supplier<T> operation) {
      return operation.get();
    }
  }

  private static Principal activeUser(OrganizationId organizationId, String name) {
    return Principal.create(
        io.crewscope.domain.shared.id.PrincipalId.generate(),
        PrincipalScope.organization(organizationId),
        PrincipalType.USER,
        Optional.empty(),
        name,
        Optional.empty(),
        PrincipalVisibility.ORGANIZATION,
        NOW);
  }
}
