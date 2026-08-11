package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingResolution;
import io.crewscope.application.provider.ProviderBindingResolutionLevel;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentCandidate;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.conversation.TaskIntentStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityAssignment;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItem;
import io.crewscope.domain.workitem.WorkItemKey;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Proves the M2-A07 orchestration boundary without replacing PostgreSQL rollback tests. */
class TaskIntentConfirmationServiceTest {

  private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-11T10:00:00Z");

  @Test
  void confirmsAndCreatesTheCompleteNativeWorkItemGraphOnce() {
    Fixture fixture = new Fixture();

    var execution = fixture.confirm("confirm-intent-1");

    assertEquals(TaskIntentStatus.CONFIRMED, execution.result().orElseThrow().taskIntent().status());
    assertEquals("CRW-1", fixture.createdWorkItem.key().value());
    assertEquals(3, fixture.assignments.size());
    assertEquals(
        List.of(
            "WORK_ITEM_CREATED",
            "WORK_ITEM_EXECUTOR_ASSIGNED",
            "WORK_ITEM_GATE_REVIEWER_ASSIGNED",
            "TASK_INTENT_CONFIRMED"),
        fixture.eventTypes());
    assertEquals(1, fixture.linksCreated);
    assertEquals(1, fixture.conversationEvents);
    assertEquals(4, fixture.outboxEvents);
    assertEquals(2, execution.receipt().committedVersion());
  }

  @Test
  void replaysTheOriginalReceiptBeforeReadingOrCreatingAnyFacts() {
    Fixture fixture = new Fixture();
    var first = fixture.confirm("confirm-intent-replay");
    fixture.replayReceipt = first.receipt();

    var replay = fixture.confirm("confirm-intent-replay");

    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(1, fixture.workItemsCreated);
    assertEquals(1, fixture.linksCreated);
  }

  @Test
  void failsClosedOnAnAmbiguousBindingBeforeCreatingTheWorkItem() {
    Fixture fixture = new Fixture();
    fixture.bindingResolution =
        ProviderBindingResolution.ambiguous(
            ProviderBindingResolutionLevel.WORKSPACE,
            List.of(ProviderBindingId.generate(), ProviderBindingId.generate()));

    DomainValidationException failure =
        assertThrows(DomainValidationException.class, () -> fixture.confirm("confirm-ambiguous"));

    assertEquals("taskIntent.providerBinding", failure.error().details().get("field"));
    assertEquals(0, fixture.workItemsCreated);
    verify(fixture.taskIntentRepository, never()).confirm(any(), any());
  }

  @Test
  void stopsBeforeConfirmationEventsWhenAnyResponsibilityCannotBeCreated() {
    Fixture fixture = new Fixture();
    when(fixture.assignmentRepository.create(any()))
        .thenAnswer(
            invocation -> {
              ResponsibilityAssignment value = invocation.getArgument(0);
              if (!fixture.assignments.isEmpty()) {
                throw new DomainValidationException(
                    "responsibilityAssignment", "simulated responsibility failure");
              }
              fixture.assignments.add(value);
              return value;
            });

    assertThrows(
        DomainValidationException.class, () -> fixture.confirm("confirm-responsibility-failure"));

    verify(fixture.taskIntentRepository, never()).confirm(any(), any());
    assertEquals(0, fixture.linksCreated);
    assertTrue(fixture.events.isEmpty());
  }

  private static final class Fixture {

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal owner = user("Owner");
    private final Principal reviewer = user("Reviewer");
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
            "Build M2-A07",
            ConversationVisibility.PRIVATE,
            NOW);
    private final WorkProject project =
        WorkProject.create(
            WorkProjectId.generate(),
            new WorkProjectKey("CRW"),
            "CrewScope",
            team.team(),
            team.defaultWorkspace(),
            owner,
            NOW);
    private final TaskIntent ready = readyIntent();

    private final TaskIntentApplicationService taskIntentService =
        mock(TaskIntentApplicationService.class);
    private final TaskIntentRepository taskIntentRepository = mock(TaskIntentRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ConversationWorkItemLinkRepository linkRepository =
        mock(ConversationWorkItemLinkRepository.class);
    private final WorkProjectRepository projectRepository = mock(WorkProjectRepository.class);
    private final WorkItemRepository workItemRepository = mock(WorkItemRepository.class);
    private final WorkItemAccessPolicy workItemAccessPolicy = mock(WorkItemAccessPolicy.class);
    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final TeamMembershipQuery membershipQuery = mock(TeamMembershipQuery.class);
    private final PrincipalRepository principalRepository = mock(PrincipalRepository.class);
    private final ResponsibilityAssignmentRepository assignmentRepository =
        mock(ResponsibilityAssignmentRepository.class);
    private final GateReviewerPolicyProvider reviewerPolicyProvider =
        ignored -> ReviewerEligibilityPolicy.strict();
    private final ProviderBindingResolver bindingResolver = mock(ProviderBindingResolver.class);
    private final DomainEventStore domainEventStore = mock(DomainEventStore.class);
    private final ConversationEventRepository conversationEventRepository =
        mock(ConversationEventRepository.class);
    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final CommandReceiptStore receiptStore = mock(CommandReceiptStore.class);
    private final BuiltInProviderRegistration registration =
        new BuiltInProviderRegistration(
            "work-item",
            ProviderType.WORK_ITEM,
            "1.0.0",
            "CrewScope WorkItem",
            "native-work-item",
            "1.0.0",
            ProviderCapabilities.of(
                "workitem.read",
                "workitem.create",
                "workitem.update",
                "workitem.comment",
                "workitem.resource-link"));
    private final List<ResponsibilityAssignment> assignments = new ArrayList<>();
    private final List<DomainEventEnvelope<?>> events = new ArrayList<>();
    private ProviderBindingResolution bindingResolution = resolvedBinding();
    private CommandReceipt replayReceipt;
    private WorkItem createdWorkItem;
    private int workItemsCreated;
    private int linksCreated;
    private int conversationEvents;
    private int outboxEvents;

    private final TaskIntentConfirmationService service = service();

    private Fixture() {
      when(taskIntentRepository.lockById(organizationId, ready.id())).thenReturn(Optional.of(ready));
      when(taskIntentService.previewConfirmation(any(), any(), any(), any(), any(Long.class)))
          .thenReturn(new TaskIntentConfirmationPreview(ready, ready.proposal(), owner.id()));
      when(conversationRepository.findById(organizationId, conversation.conversation().id()))
          .thenReturn(Optional.of(conversation.conversation()));
      when(workItemAccessPolicy.requireCreatePermission(any(), any(), any(), any(), any()))
          .thenReturn(project);
      when(projectRepository.lockById(organizationId, project.id()))
          .thenReturn(Optional.of(project));
      when(teamRepository.findById(organizationId, team.team().id()))
          .thenReturn(Optional.of(team.team()));
      when(bindingResolver.resolve(any())).thenAnswer(ignored -> bindingResolution);
      when(workItemRepository.nextKey(organizationId, project))
          .thenReturn(new WorkItemKey("CRW-1"));
      when(workItemRepository.create(any()))
          .thenAnswer(
              invocation -> {
                workItemsCreated++;
                createdWorkItem = invocation.getArgument(0);
                return createdWorkItem;
              });
      when(membershipQuery.findByTeam(organizationId, team.team().id()))
          .thenReturn(List.of(team.ownerMember(), reviewerMember));
      when(principalRepository.findById(any(), any()))
          .thenAnswer(
              invocation -> {
                Object id = invocation.getArgument(1);
                if (owner.id().equals(id)) {
                  return Optional.of(owner);
                }
                if (reviewer.id().equals(id)) {
                  return Optional.of(reviewer);
                }
                if (team.ownerPersonalAgent().agentPrincipal().id().equals(id)) {
                  return Optional.of(team.ownerPersonalAgent().agentPrincipal());
                }
                return Optional.empty();
              });
      when(assignmentRepository.create(any()))
          .thenAnswer(
              invocation -> {
                ResponsibilityAssignment value = invocation.getArgument(0);
                assignments.add(value);
                return value;
              });
      when(taskIntentRepository.confirm(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
      when(linkRepository.create(any()))
          .thenAnswer(
              invocation -> {
                linksCreated++;
                return invocation.getArgument(0);
              });
      when(receiptStore.reserve(any()))
          .thenAnswer(
              ignored ->
                  replayReceipt == null
                      ? CommandReservation.newlyAcquired()
                      : CommandReservation.replay(replayReceipt));
      doAnswer(
              invocation -> {
                events.add(invocation.getArgument(0));
                return null;
              })
          .when(domainEventStore)
          .append(any());
      doAnswer(
              invocation -> {
                conversationEvents++;
                return null;
              })
          .when(conversationEventRepository)
          .append(any(), any());
      doAnswer(
              invocation -> {
                outboxEvents++;
                return null;
              })
          .when(outboxRepository)
          .enqueue(any());
    }

    private io.crewscope.application.command.CommandExecution<TaskIntentConfirmationResult> confirm(
        String key) {
      return service.confirm(
          new TeamCommandContext(
              new TeamAccessContext(owner, false),
              IdempotencyKey.from(key),
              UUID.randomUUID(),
              Optional.empty()),
          team.team().id(),
          new ConversationIdAndTaskIntentId(conversation.conversation().id(), ready.id()),
          new ConfirmTaskIntentCommand(ready.version()));
    }

    private List<String> eventTypes() {
      return events.stream().map(value -> value.eventType().value()).toList();
    }

    private TaskIntentConfirmationService service() {
      TransactionExecutor transactions =
          new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
              return operation.get();
            }
          };
      TimeProvider timeProvider = () -> NOW;
      return new TaskIntentConfirmationService(
          taskIntentService,
          taskIntentRepository,
          conversationRepository,
          linkRepository,
          projectRepository,
          workItemRepository,
          workItemAccessPolicy,
          teamRepository,
          membershipQuery,
          principalRepository,
          assignmentRepository,
          reviewerPolicyProvider,
          registration,
          bindingResolver,
          domainEventStore,
          conversationEventRepository,
          outboxRepository,
          receiptStore,
          transactions,
          timeProvider);
    }

    private ProviderBindingResolution resolvedBinding() {
      ProviderBinding binding = mock(ProviderBinding.class);
      ProviderDefinition definition = mock(ProviderDefinition.class);
      ProviderImplementation implementation = mock(ProviderImplementation.class);
      when(binding.id()).thenReturn(ProviderBindingId.generate());
      when(definition.id()).thenReturn(registration.definitionId(organizationId));
      when(implementation.id()).thenReturn(registration.implementationId(organizationId));
      ProviderBindingCandidate candidate =
          new ProviderBindingCandidate(
              binding,
              definition,
              implementation,
              Optional.empty(),
              Optional.empty(),
              registration.workspaceAccess(team.defaultWorkspace().id()));
      return ProviderBindingResolution.resolved(
          ProviderBindingResolutionLevel.WORKSPACE, candidate);
    }

    private TaskIntent readyIntent() {
      TaskIntentProposal proposal =
          TaskIntentProposal.create(
              conversation.conversation(),
              project,
              "Ship the atomic confirmation workflow",
              List.of("Creates the WorkItem", "Preserves all responsibility facts"),
              TaskIntentCandidate.user(owner, team.ownerMember()),
              Optional.of(
                  TaskIntentCandidate.agent(team.ownerPersonalAgent().agentPrincipal())),
              Optional.of(TaskIntentCandidate.user(reviewer, reviewerMember)));
      return TaskIntent.draft(
              TaskIntentId.generate(),
              conversation.conversation(),
              conversation.agentParticipant(),
              team.ownerPersonalAgent().agentPrincipal(),
              proposal,
              NOW)
          .markReady(0, team.ownerPersonalAgent().agentPrincipal(), NOW);
    }

    private Principal user(String name) {
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
}
