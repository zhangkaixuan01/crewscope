package io.crewscope.infrastructure.persistence.m2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.application.conversation.ConversationApplicationService;
import io.crewscope.application.conversation.ConversationDetails;
import io.crewscope.application.conversation.ConversationEventRepository;
import io.crewscope.application.conversation.ConversationEventCursorExpiredException;
import io.crewscope.application.conversation.ConversationEventPage;
import io.crewscope.application.conversation.ConversationEventQuery;
import io.crewscope.application.conversation.ConversationPage;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationQuery;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.conversation.ConversationWorkItemLinkRepository;
import io.crewscope.application.conversation.MessageHistoryQuery;
import io.crewscope.application.conversation.MessagePage;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.PostConversationMessageCommand;
import io.crewscope.application.conversation.ConversationIdAndTaskIntentId;
import io.crewscope.application.conversation.ConfirmTaskIntentCommand;
import io.crewscope.application.conversation.ReviseTaskIntentCommand;
import io.crewscope.application.conversation.TaskIntentApplicationService;
import io.crewscope.application.conversation.TaskIntentConfirmationService;
import io.crewscope.application.conversation.TaskIntentRepository;
import io.crewscope.application.conversation.TaskIntentV1;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.execution.AgentMessageCandidate;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.application.execution.RealtimeStreamEventIds;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.BuiltInProviderRegistration;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingQuery;
import io.crewscope.application.provider.ProviderBindingCandidate;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderBindingResolution;
import io.crewscope.application.provider.ProviderBindingResolutionLevel;
import io.crewscope.application.provider.ProviderBindingResolutionRequest;
import io.crewscope.application.provider.ProviderBindingResolutionStatus;
import io.crewscope.application.provider.ProviderBindingResolver;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.responsibility.GateReviewerPolicyProvider;
import io.crewscope.application.responsibility.ResponsibilityAssignmentRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkItemRepository;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.AgentRuntimeSessionStatus;
import io.crewscope.domain.conversation.AgentRuntimeStateReference;
import io.crewscope.domain.conversation.AgentScopeSessionKey;
import io.crewscope.domain.conversation.Conversation;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.conversation.ConversationMessageAppend;
import io.crewscope.domain.conversation.ConversationParticipant;
import io.crewscope.domain.conversation.ConversationParticipantId;
import io.crewscope.domain.conversation.ConversationParticipantRole;
import io.crewscope.domain.conversation.ConversationParticipantStatus;
import io.crewscope.domain.conversation.ConversationScope;
import io.crewscope.domain.conversation.ConversationStatus;
import io.crewscope.domain.conversation.ConversationVisibility;
import io.crewscope.domain.conversation.ConversationVisibilityPolicy;
import io.crewscope.domain.conversation.MessageContent;
import io.crewscope.domain.conversation.MessageId;
import io.crewscope.domain.conversation.TaskIntent;
import io.crewscope.domain.conversation.TaskIntentDecision;
import io.crewscope.domain.conversation.TaskIntentId;
import io.crewscope.domain.conversation.TaskIntentProposal;
import io.crewscope.domain.conversation.TaskIntentResponsibility;
import io.crewscope.domain.conversation.TaskIntentStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ConnectionGrantId;
import io.crewscope.domain.provider.ConnectionGrantStatus;
import io.crewscope.domain.provider.ConnectionId;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderAccessScope;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTarget;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderCapabilities;
import io.crewscope.domain.provider.ProviderConnectionRequirement;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderDefinitionId;
import io.crewscope.domain.provider.ProviderExecutionIdentity;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.responsibility.ReviewerEligibilityPolicy;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.shared.event.StreamType;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectKey;
import io.crewscope.domain.workitem.WorkProjectScope;
import io.crewscope.domain.workitem.WorkProjectStatus;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.infrastructure.persistence.command.JdbcCommandReceiptStore;
import io.crewscope.infrastructure.persistence.conversation.ConversationPersistenceMapper;
import io.crewscope.infrastructure.persistence.conversation.JpaAgentRuntimeSessionRepositoryAdapter;
import io.crewscope.infrastructure.persistence.conversation.JpaConversationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.conversation.JdbcConversationEventRepository;
import io.crewscope.infrastructure.persistence.event.JdbcDomainEventStore;
import io.crewscope.infrastructure.persistence.event.JdbcOutboxRepository;
import io.crewscope.infrastructure.persistence.provider.JpaProviderRepositoryAdapter;
import io.crewscope.infrastructure.persistence.provider.ProviderPersistenceMapper;
import io.crewscope.infrastructure.persistence.responsibility.JpaResponsibilityAssignmentRepositoryAdapter;
import io.crewscope.infrastructure.persistence.responsibility.ResponsibilityPersistenceMapper;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkItemRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.JpaWorkProjectRepositoryAdapter;
import io.crewscope.infrastructure.persistence.workitem.WorkItemEntityMapper;
import io.crewscope.infrastructure.persistence.workitem.WorkPersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import io.crewscope.infrastructure.transaction.SpringTransactionExecutor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves M2 mappings, locks, idempotency, keysets and Scope queries on PostgreSQL. */
@SpringBootTest(
        classes = M2JpaPersistenceIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.open-in-view=false"
        })
class M2JpaPersistenceIntegrationTest extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp BASE_TIME = UtcTimestamp.parse("2026-08-08T12:00:00Z");

    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationWorkItemLinkRepository conversationWorkItemLinkRepository;
    @Autowired private ConversationParticipantRepository participantRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ConversationEventRepository conversationEventRepository;
    @Autowired private TaskIntentRepository taskIntentRepository;
    @Autowired private AgentRuntimeSessionRepository sessionRepository;
    @Autowired private ProviderDefinitionRepository definitionRepository;
    @Autowired private ProviderImplementationRepository implementationRepository;
    @Autowired private ProviderBindingRepository bindingRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectionGrantRepository connectionGrantRepository;
    @Autowired private DomainEventStore domainEventStore;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private CommandReceiptStore commandReceiptStore;
    @Autowired private WorkProjectRepository workProjectRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ResponsibilityAssignmentRepository responsibilityAssignmentRepository;
    @Autowired private TransactionExecutor transactionExecutor;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetBusinessData() {
        jdbcTemplate.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void roundTripsConversationMessagesAndStableKeysetPages() {
        Fixture fixture = seedFixture("mapping");
        Conversation first = conversation(fixture, ConversationId.generate(), BASE_TIME, "First");
        Conversation second = conversation(
                fixture,
                ConversationId.generate(),
                UtcTimestamp.parse("2026-08-08T12:01:00Z"),
                "Second");
        conversationRepository.create(first);
        conversationRepository.create(second);
        ConversationParticipant participant = participant(fixture, first);
        participantRepository.create(participant);
        participantRepository.create(participant(fixture, second));

        ConversationMessageAppend firstAppend = first.appendMessage(
                MessageId.generate(),
                participant,
                fixture.ownerPrincipal(),
                new MessageContent("first message"),
                UtcTimestamp.parse("2026-08-08T12:02:00Z"));
        conversationRepository.update(firstAppend.conversation());
        assertEquals(
                firstAppend.message().id(),
                messageRepository.create(firstAppend.message(), Optional.of("client-1")).id());
        assertEquals(
                firstAppend.message().id(),
                messageRepository.create(firstAppend.message(), Optional.of("client-1")).id());

        ConversationMessageAppend secondAppend = firstAppend.conversation().appendMessage(
                MessageId.generate(),
                participant,
                fixture.ownerPrincipal(),
                new MessageContent("second message"),
                UtcTimestamp.parse("2026-08-08T12:03:00Z"));
        conversationRepository.update(secondAppend.conversation());
        messageRepository.create(secondAppend.message(), Optional.empty());

        ConversationPage pageOne = conversationRepository.findPage(new ConversationQuery(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.ownerPrincipal().id(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1));
        ConversationPage pageTwo = conversationRepository.findPage(new ConversationQuery(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.ownerPrincipal().id(),
                Optional.empty(),
                Optional.empty(),
                pageOne.nextCursor(),
                1));
        MessagePage messageOne = messageRepository.findPage(new MessageHistoryQuery(
                fixture.scope(), first.id(), Optional.empty(), Optional.empty(), 1));
        MessagePage messageTwo = messageRepository.findPage(new MessageHistoryQuery(
                fixture.scope(), first.id(), Optional.empty(), messageOne.nextCursor(), 1));

        assertEquals(2, Set.of(pageOne.conversations().get(0).id(), pageTwo.conversations().get(0).id()).size());
        assertEquals(List.of(2L), messageOne.messages().stream().map(value -> value.sequence().value()).toList());
        assertEquals(List.of(1L), messageTwo.messages().stream().map(value -> value.sequence().value()).toList());
        assertTrue(messageRepository
                .findByClientMessageKey(fixture.organizationId(), first.id(), "client-1")
                .isPresent());
    }

    @Test
    void filtersPrivateConversationPagesAndAppliesHistoricalMessageCutoffs() {
        Fixture fixture = seedFixture("visibility");
        Conversation visiblePrivate = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "Visible private");
        Conversation hiddenPrivate = conversation(
                fixture,
                ConversationId.generate(),
                UtcTimestamp.parse("2026-08-08T12:01:00Z"),
                "Hidden private");
        Conversation visibleTeam = Conversation.reconstitute(
                ConversationId.generate(),
                fixture.scope(),
                fixture.ownerMemberId(),
                fixture.ownerPrincipalId(),
                fixture.agentPrincipalId(),
                "Visible team",
                ConversationVisibility.TEAM,
                ConversationStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(
                        fixture.ownerPrincipalId(),
                        UtcTimestamp.parse("2026-08-08T12:02:00Z")));
        conversationRepository.create(visiblePrivate);
        conversationRepository.create(hiddenPrivate);
        conversationRepository.create(visibleTeam);
        ConversationParticipant participant =
                participantRepository.create(participant(fixture, visiblePrivate));

        ConversationMessageAppend first = visiblePrivate.appendMessage(
                MessageId.generate(),
                participant,
                fixture.ownerPrincipal(),
                new MessageContent("visible before leaving"),
                UtcTimestamp.parse("2026-08-08T12:03:00Z"));
        conversationRepository.update(first.conversation());
        messageRepository.create(first.message(), Optional.empty());
        ConversationMessageAppend second = first.conversation().appendMessage(
                MessageId.generate(),
                participant,
                fixture.ownerPrincipal(),
                new MessageContent("hidden after leaving"),
                UtcTimestamp.parse("2026-08-08T12:04:00Z"));
        conversationRepository.update(second.conversation());
        messageRepository.create(second.message(), Optional.empty());

        ConversationPage page = conversationRepository.findPage(new ConversationQuery(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.ownerPrincipalId(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                10));
        MessagePage history = messageRepository.findPage(new MessageHistoryQuery(
                fixture.scope(),
                visiblePrivate.id(),
                Optional.of(UtcTimestamp.parse("2026-08-08T12:03:30Z")),
                Optional.empty(),
                10));

        assertEquals(
                Set.of(visiblePrivate.id(), visibleTeam.id()),
                page.conversations().stream().map(Conversation::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                List.of(1L),
                history.messages().stream().map(value -> value.sequence().value()).toList());
    }

    @Test
    void rejectsAStaleConversationMutationWithTheCommittedVersion() {
        Fixture fixture = seedFixture("optimistic");
        Conversation created = conversation(fixture, ConversationId.generate(), BASE_TIME, "Locking");
        conversationRepository.create(created);
        Conversation first = created.changeVisibility(
                ConversationVisibility.TEAM,
                fixture.ownerPrincipal(),
                UtcTimestamp.parse("2026-08-08T12:01:00Z"));
        Conversation stale = created.archive(
                fixture.ownerPrincipal(), UtcTimestamp.parse("2026-08-08T12:02:00Z"));

        conversationRepository.update(first);
        OptimisticLockConflictException failure = assertThrows(
                OptimisticLockConflictException.class,
                () -> conversationRepository.update(stale));

        assertEquals("0", failure.error().details().get("expectedVersion"));
        assertEquals("1", failure.error().details().get("actualVersion"));
    }

    @Test
    void failsClosedForWorkspaceScopedUpdatesPresentedWithTheWrongScope() {
        Fixture fixture = seedFixture("scope-update");
        UtcTimestamp mutationTime = UtcTimestamp.parse("2026-08-08T12:05:00Z");
        WorkspaceId wrongWorkspaceId = WorkspaceId.generate();
        ConversationScope wrongScope = new ConversationScope(
                fixture.organizationId(), fixture.teamId(), wrongWorkspaceId);
        Conversation conversation = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "Scoped writes");
        conversationRepository.create(conversation);

        ConversationParticipant committedParticipant =
                participantRepository.create(participant(fixture, conversation));
        ConversationParticipant wrongScopeParticipant = ConversationParticipant.reconstitute(
                committedParticipant.id(),
                wrongScope,
                committedParticipant.conversationId(),
                committedParticipant.principalId(),
                committedParticipant.teamMemberId(),
                committedParticipant.role(),
                committedParticipant.status(),
                committedParticipant.joinedByPrincipalId(),
                committedParticipant.joinedAt(),
                committedParticipant.leftAt(),
                1,
                committedParticipant
                        .audit()
                        .modifiedBy(fixture.ownerPrincipalId(), mutationTime));

        TaskIntent committedIntent =
                taskIntentRepository.create(readyIntent(fixture, conversation));
        TaskIntentProposal wrongScopeProposal = new TaskIntentProposal(
                new WorkItemScope(
                        fixture.organizationId(),
                        fixture.teamId(),
                        wrongWorkspaceId,
                        fixture.workProjectId()),
                committedIntent.proposal().objective(),
                committedIntent.proposal().acceptanceCriteria(),
                committedIntent.proposal().owner(),
                committedIntent.proposal().executor(),
                committedIntent.proposal().gateReviewer());
        TaskIntent wrongScopeIntent = TaskIntent.reconstitute(
                committedIntent.id(),
                wrongScope,
                committedIntent.conversationId(),
                committedIntent.proposedByPrincipalId(),
                committedIntent.proposalRevision(),
                wrongScopeProposal,
                TaskIntentStatus.REJECTED,
                Optional.of(TaskIntentDecision.rejected(
                        fixture.ownerPrincipalId(), "scope changed", mutationTime)),
                1,
                committedIntent
                        .audit()
                        .modifiedBy(fixture.ownerPrincipalId(), mutationTime));

        ProviderDefinition definition = definitionRepository.create(providerDefinition(fixture));
        ProviderImplementation implementation = implementationRepository.create(
                providerImplementation(fixture, definition));
        ProviderBinding committedBinding = bindingRepository.create(providerBinding(
                fixture,
                definition,
                implementation,
                ProviderOwner.organization(fixture.organizationId()),
                ProviderBindingTargetType.WORKSPACE,
                true));
        ProviderBinding disabledBinding = committedBinding.disable(
                0, fixture.ownerPrincipal(), mutationTime);
        ProviderBinding wrongScopeBinding = ProviderBinding.reconstitute(
                disabledBinding.id(),
                disabledBinding.organizationId(),
                new ProviderBindingTarget(
                        fixture.organizationId(),
                        fixture.teamId(),
                        wrongWorkspaceId,
                        ProviderBindingTargetType.WORKSPACE,
                        Optional.empty()),
                disabledBinding.owner(),
                disabledBinding.definitionId(),
                disabledBinding.definitionVersion(),
                disabledBinding.providerType(),
                disabledBinding.implementationId(),
                disabledBinding.implementationVersion(),
                disabledBinding.connectionId(),
                disabledBinding.connectionVersion(),
                disabledBinding.connectionGrantId(),
                disabledBinding.connectionGrantVersion(),
                disabledBinding.executionIdentity(),
                disabledBinding.effectiveAccess(),
                disabledBinding.defaultUsage(),
                disabledBinding.status(),
                disabledBinding.version(),
                disabledBinding.audit());

        assertThrows(
                AggregateNotFoundException.class,
                () -> participantRepository.update(wrongScopeParticipant));
        assertThrows(
                AggregateNotFoundException.class,
                () -> taskIntentRepository.update(wrongScopeIntent));
        assertThrows(
                AggregateNotFoundException.class,
                () -> bindingRepository.update(wrongScopeBinding));
        assertEquals(
                TaskIntentStatus.READY,
                taskIntentRepository
                        .findById(fixture.organizationId(), committedIntent.id())
                        .orElseThrow()
                        .status());
        assertEquals(
                ProviderRegistrationStatus.ACTIVE,
                bindingRepository
                        .findById(fixture.organizationId(), committedBinding.id())
                        .orElseThrow()
                        .status());
    }

    @Test
    void serializesConcurrentMessageSequenceAllocationOnTheConversationRow() throws Exception {
        Fixture fixture = seedFixture("message-concurrency");
        Conversation created = conversation(fixture, ConversationId.generate(), BASE_TIME, "Concurrent");
        conversationRepository.create(created);
        ConversationParticipant participant = participant(fixture, created);
        participantRepository.create(participant);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 2; index++) {
                int messageNumber = index;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        transaction.executeWithoutResult(ignored -> {
                            Conversation locked = conversationRepository
                                    .lockById(fixture.organizationId(), created.id())
                                    .orElseThrow();
                            ConversationMessageAppend append = locked.appendMessage(
                                    MessageId.generate(),
                                    participant,
                                    fixture.ownerPrincipal(),
                                    new MessageContent("concurrent " + messageNumber),
                                    UtcTimestamp.parse("2026-08-08T12:03:00Z"));
                            conversationRepository.update(append.conversation());
                            messageRepository.create(append.message(), Optional.of("concurrent-" + messageNumber));
                        });
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertTrue(failures.isEmpty(), () -> "concurrent append failures: " + failures);
        List<Long> sequences = jdbcTemplate.queryForList(
                "SELECT sequence FROM crewscope.message WHERE conversation_id = ? ORDER BY sequence",
                Long.class,
                created.id().value());
        assertEquals(List.of(1L, 2L), sequences);
    }

    @Test
    void commitsAndReplaysUserMessageWithEventOutboxAndReceipt() {
        Fixture fixture = seedFixture("message-command");
        Conversation created =
                conversation(fixture, ConversationId.generate(), BASE_TIME, "Message command");
        conversationRepository.create(created);
        participantRepository.create(participant(fixture, created));
        ConversationApplicationService service = conversationService(fixture, outboxRepository);
        TeamCommandContext context = messageContext(fixture, "message-command-1");
        PostConversationMessageCommand command =
                PostConversationMessageCommand.fromMarkdown("**Persist** this message.");

        CommandExecution<ConversationMessageAppend> first =
                service.postUserMessage(context, fixture.teamId(), created.id(), command);
        CommandExecution<ConversationMessageAppend> replay =
                service.postUserMessage(context, fixture.teamId(), created.id(), command);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(1L, first.result().orElseThrow().message().sequence().value());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.message", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.domain_event", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.conversation_event", Integer.class));
        Map<String, Object> event =
                jdbcTemplate.queryForMap(
                        "SELECT event_type, subject_type, subject_id, aggregate_version, payload::TEXT AS payload FROM crewscope.domain_event");
        assertEquals("CONVERSATION_MESSAGE_POSTED", event.get("event_type"));
        assertEquals("CONVERSATION", event.get("subject_type"));
        assertEquals(created.id().value(), event.get("subject_id"));
        assertEquals(1L, ((Number) event.get("aggregate_version")).longValue());
        assertTrue(((String) event.get("payload")).contains("Persist"));

        assertThrows(
                IdempotencyConflictException.class,
                () ->
                        service.postUserMessage(
                                context,
                                fixture.teamId(),
                                created.id(),
                                PostConversationMessageCommand.fromMarkdown("Changed")));
    }

    @Test
    void rollsBackConversationMessageEventAndReceiptWhenOutboxFails() {
        Fixture fixture = seedFixture("message-rollback");
        Conversation created =
                conversation(fixture, ConversationId.generate(), BASE_TIME, "Rollback");
        conversationRepository.create(created);
        participantRepository.create(participant(fixture, created));
        OutboxRepository failingOutbox = ignored -> {
            throw new SimulatedMessageOutboxFailure();
        };

        assertThrows(
                SimulatedMessageOutboxFailure.class,
                () ->
                        conversationService(fixture, failingOutbox)
                                .postUserMessage(
                                        messageContext(fixture, "message-rollback-1"),
                                        fixture.teamId(),
                                        created.id(),
                                        PostConversationMessageCommand.fromMarkdown(
                                                "Must roll back")));

        Conversation unchanged =
                conversationRepository
                        .findById(fixture.organizationId(), created.id())
                        .orElseThrow();
        assertEquals(0, unchanged.version());
        assertTrue(unchanged.lastMessageSequence().isEmpty());
        for (String table : List.of(
                "message", "domain_event", "conversation_event", "outbox_event", "command_receipt")) {
            assertEquals(
                    0,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM crewscope." + table, Integer.class));
        }
    }

    @Test
    void pagesConversationEventsByDurablePositionAndRejectsCompactedCursor() {
        Fixture fixture = seedFixture("conversation-event-page");
        Conversation created =
                conversation(fixture, ConversationId.generate(), BASE_TIME, "Event stream");
        conversationRepository.create(created);
        participantRepository.create(participant(fixture, created));
        ConversationApplicationService service = conversationService(fixture, outboxRepository);
        service.postUserMessage(
                messageContext(fixture, "conversation-event-1"),
                fixture.teamId(),
                created.id(),
                PostConversationMessageCommand.fromMarkdown("first"));
        service.postUserMessage(
                messageContext(fixture, "conversation-event-2"),
                fixture.teamId(),
                created.id(),
                PostConversationMessageCommand.fromMarkdown("second"));

        ConversationEventPage first = conversationEventRepository.findPage(
                new ConversationEventQuery(
                        created.scope(), created.id(), Optional.empty(), Optional.empty(), 1));
        assertEquals(1, first.events().size());
        assertTrue(first.hasMore());
        var firstEvent = first.events().get(0);
        assertEquals(
                RealtimeStreamEventIds.forDomain(
                        StreamType.CONVERSATION,
                        firstEvent.envelope().domainEventId().orElseThrow()),
                firstEvent.envelope().eventId());

        ConversationEventPage second = conversationEventRepository.findPage(
                new ConversationEventQuery(
                        created.scope(),
                        created.id(),
                        Optional.empty(),
                        first.nextCursor(),
                        10));
        assertEquals(1, second.events().size());
        assertFalse(second.hasMore());
        assertTrue(
                second.events().get(0).cursor().position()
                        > firstEvent.cursor().position());

        jdbcTemplate.update(
                "DELETE FROM crewscope.conversation_event WHERE position = ?",
                firstEvent.cursor().position());
        assertThrows(
                ConversationEventCursorExpiredException.class,
                () ->
                        conversationEventRepository.findPage(
                                new ConversationEventQuery(
                                        created.scope(),
                                        created.id(),
                                        Optional.empty(),
                                        first.nextCursor(),
                                        10)));
    }

    @Test
    void commitsAndReplaysAgentReplyWithoutCommandReceipt() {
        Fixture fixture = seedFixture("agent-message");
        Conversation created =
                conversation(fixture, ConversationId.generate(), BASE_TIME, "Agent message");
        conversationRepository.create(created);
        ConversationParticipant agentParticipant = agentParticipant(fixture, created);
        participantRepository.create(agentParticipant);
        ConversationApplicationService service = conversationService(fixture, outboxRepository);
        RuntimeInvocationId invocationId = RuntimeInvocationId.generate();
        UUID segmentId = UUID.randomUUID();
        AgentMessageCandidate candidate = new AgentMessageCandidate(
                invocationId,
                segmentId,
                created.id(),
                agentParticipant.id(),
                fixture.agentPrincipalId(),
                new MessageContent("Persisted Agent reply"),
                UtcTimestamp.parse("2026-08-08T12:06:00Z"));

        io.crewscope.domain.conversation.Message first = service.commitAgentMessage(
                candidate, fixture.organizationId(), UUID.randomUUID(), Optional.empty());
        io.crewscope.domain.conversation.Message replay = service.commitAgentMessage(
                candidate, fixture.organizationId(), UUID.randomUUID(), Optional.empty());

        assertEquals(first.id(), replay.id());
        assertEquals(1L, first.sequence().value());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.message", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.domain_event", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
        assertEquals(
                0,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
        assertEquals(
                "PERSONAL_AGENT",
                jdbcTemplate.queryForObject(
                        "SELECT actor_type FROM crewscope.domain_event", String.class));
    }

    @Test
    void rollsBackAgentReplyConversationEventAndOutboxTogether() {
        Fixture fixture = seedFixture("agent-message-rollback");
        Conversation created = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "Agent message rollback");
        conversationRepository.create(created);
        ConversationParticipant agentParticipant = agentParticipant(fixture, created);
        participantRepository.create(agentParticipant);
        OutboxRepository failingOutbox = ignored -> {
            throw new SimulatedMessageOutboxFailure();
        };
        AgentMessageCandidate candidate = new AgentMessageCandidate(
                RuntimeInvocationId.generate(),
                UUID.randomUUID(),
                created.id(),
                agentParticipant.id(),
                fixture.agentPrincipalId(),
                new MessageContent("Must roll back"),
                UtcTimestamp.parse("2026-08-08T12:06:00Z"));

        assertThrows(
                SimulatedMessageOutboxFailure.class,
                () -> conversationService(fixture, failingOutbox)
                        .commitAgentMessage(
                                candidate,
                                fixture.organizationId(),
                                UUID.randomUUID(),
                                Optional.empty()));

        Conversation unchanged = conversationRepository
                .findById(fixture.organizationId(), created.id())
                .orElseThrow();
        assertEquals(0, unchanged.version());
        assertTrue(unchanged.lastMessageSequence().isEmpty());
        for (String table : List.of("message", "domain_event", "outbox_event")) {
            assertEquals(
                    0,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM crewscope." + table, Integer.class));
        }
    }

    @Test
    void revisesTaskIntentThroughTwoVersionStepsWithOneTransactionalEventAndReceipt() {
        Fixture fixture = seedFixture("intent-revision");
        Conversation conversation = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "Intent revision");
        conversationRepository.create(conversation);
        TaskIntent ready = taskIntentRepository.create(readyIntent(fixture, conversation));
        TaskIntentApplicationService service =
                taskIntentService(fixture, conversation, outboxRepository);

        CommandExecution<TaskIntent> execution = service.revise(
                messageContext(fixture, "intent-revision-1"),
                fixture.teamId(),
                new ConversationIdAndTaskIntentId(conversation.id(), ready.id()),
                new ReviseTaskIntentCommand(new TaskIntentV1(
                        TaskIntentV1.SCHEMA_VERSION,
                        "Persist the revised intent",
                        List.of("Revision and readiness are atomic"),
                        fixture.workProjectId().toString(),
                        fixture.ownerMemberId().toString(),
                        null,
                        null)),
                ready.version());

        TaskIntent committed = taskIntentRepository
                .findById(fixture.organizationId(), ready.id())
                .orElseThrow();
        assertEquals(TaskIntentStatus.READY, committed.status());
        assertEquals(2, committed.proposalRevision());
        assertEquals(2, committed.version());
        assertEquals("Persist the revised intent", committed.proposal().objective());
        assertEquals(committed.version(), execution.receipt().committedVersion());
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.domain_event", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.conversation_event", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.command_receipt", Integer.class));
        Map<String, Object> event = jdbcTemplate.queryForMap(
                "SELECT event_type, subject_type, aggregate_version FROM crewscope.domain_event");
        assertEquals("TASK_INTENT_REVISED", event.get("event_type"));
        assertEquals("TASK_INTENT", event.get("subject_type"));
        assertEquals(2L, ((Number) event.get("aggregate_version")).longValue());
    }

    @Test
    void rollsBackBothTaskIntentRevisionStepsAndPublicationWhenOutboxFails() {
        Fixture fixture = seedFixture("intent-revision-rollback");
        Conversation conversation = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "Intent rollback");
        conversationRepository.create(conversation);
        TaskIntent ready = taskIntentRepository.create(readyIntent(fixture, conversation));
        OutboxRepository failingOutbox = ignored -> {
            throw new SimulatedMessageOutboxFailure();
        };

        assertThrows(
                SimulatedMessageOutboxFailure.class,
                () -> taskIntentService(fixture, conversation, failingOutbox).revise(
                        messageContext(fixture, "intent-revision-rollback-1"),
                        fixture.teamId(),
                        new ConversationIdAndTaskIntentId(conversation.id(), ready.id()),
                        new ReviseTaskIntentCommand(new TaskIntentV1(
                                TaskIntentV1.SCHEMA_VERSION,
                                "Must roll back",
                                List.of("No partial draft survives"),
                                fixture.workProjectId().toString(),
                                fixture.ownerMemberId().toString(),
                                null,
                                null)),
                        ready.version()));

        TaskIntent unchanged = taskIntentRepository
                .findById(fixture.organizationId(), ready.id())
                .orElseThrow();
        assertEquals(TaskIntentStatus.READY, unchanged.status());
        assertEquals(1, unchanged.proposalRevision());
        assertEquals(0, unchanged.version());
        assertEquals("Implement persisted intent", unchanged.proposal().objective());
        for (String table : List.of(
                "domain_event", "conversation_event", "outbox_event", "command_receipt")) {
            assertEquals(
                    0,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM crewscope." + table, Integer.class));
        }
    }

    @Test
    void serializesConcurrentUserMessageCommandsIntoDistinctSequences() throws Exception {
        Fixture fixture = seedFixture("message-service-concurrency");
        Conversation created =
                conversation(fixture, ConversationId.generate(), BASE_TIME, "Service concurrency");
        conversationRepository.create(created);
        participantRepository.create(participant(fixture, created));
        ConversationApplicationService service = conversationService(fixture, outboxRepository);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Future<CommandExecution<ConversationMessageAppend>>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                int messageNumber = index;
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await(10, TimeUnit.SECONDS);
                                    return service.postUserMessage(
                                            messageContext(
                                                    fixture,
                                                    "message-service-" + messageNumber),
                                            fixture.teamId(),
                                            created.id(),
                                            PostConversationMessageCommand.fromMarkdown(
                                                    "message " + messageNumber));
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<CommandExecution<ConversationMessageAppend>> future : futures) {
                assertFalse(future.get().replayed());
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(
                List.of(1L, 2L),
                jdbcTemplate.queryForList(
                        "SELECT sequence FROM crewscope.message ORDER BY sequence", Long.class));
        assertEquals(
                List.of(1L, 2L),
                jdbcTemplate.queryForList(
                        "SELECT aggregate_version FROM crewscope.domain_event ORDER BY aggregate_version",
                        Long.class));
    }

    @Test
    void confirmsTaskIntentOnceAndBindsExactlyOneWorkItem() throws Exception {
        Fixture fixture = seedFixture("intent");
        Conversation conversation = conversation(fixture, ConversationId.generate(), BASE_TIME, "Intent");
        conversationRepository.create(conversation);
        TaskIntent ready = readyIntent(fixture, conversation);
        taskIntentRepository.create(ready);
        WorkItemId firstWorkItem = seedWorkItem(fixture, "INT-1");
        WorkItemId secondWorkItem = seedWorkItem(fixture, "INT-2");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch readyWriters = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        var executor = Executors.newFixedThreadPool(2);
        try {
            for (WorkItemId workItemId : List.of(firstWorkItem, secondWorkItem)) {
                executor.submit(() -> {
                    readyWriters.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        transaction.executeWithoutResult(ignored -> {
                            TaskIntent locked = taskIntentRepository
                                    .lockById(fixture.organizationId(), ready.id())
                                    .orElseThrow();
                            TaskIntent confirmed =
                                    confirmedIntent(locked, fixture.ownerPrincipalId());
                            taskIntentRepository.confirm(confirmed, workItemId);
                        });
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            assertTrue(readyWriters.await(10, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, failures.size());
        assertEquals(
                TaskIntentStatus.CONFIRMED,
                taskIntentRepository.findById(fixture.organizationId(), ready.id()).orElseThrow().status());
        assertTrue(taskIntentRepository
                .findConfirmedWorkItemId(fixture.organizationId(), ready.id())
                .isPresent());
    }

    @Test
    void confirmsTaskIntentAndCreatesNativeWorkItemFactsInOnePostgresTransaction() {
        Fixture fixture = seedFixture("a07-success");
        Conversation conversation = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "A07 success");
        conversationRepository.create(conversation);
        TaskIntent ready = taskIntentRepository.create(readyIntent(fixture, conversation));
        TaskIntentConfirmationService service =
                confirmationService(fixture, conversation, outboxRepository);
        TeamCommandContext context = messageContext(fixture, "a07-confirm-success");

        CommandExecution<?> first = service.confirm(
                context,
                fixture.teamId(),
                new ConversationIdAndTaskIntentId(conversation.id(), ready.id()),
                new ConfirmTaskIntentCommand(ready.version()));
        CommandExecution<?> replay = service.confirm(
                context,
                fixture.teamId(),
                new ConversationIdAndTaskIntentId(conversation.id(), ready.id()),
                new ConfirmTaskIntentCommand(ready.version()));

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(
                TaskIntentStatus.CONFIRMED,
                taskIntentRepository
                        .findById(fixture.organizationId(), ready.id())
                        .orElseThrow()
                        .status());
        for (String table : List.of(
                "work_item",
                "responsibility_assignment",
                "conversation_work_item_link",
                "command_receipt",
                "conversation_event")) {
            assertEquals(
                    1,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM crewscope." + table, Integer.class));
        }
        assertEquals(
                List.of("TASK_INTENT_CONFIRMED", "WORK_ITEM_CREATED"),
                jdbcTemplate.queryForList(
                        "SELECT event_type FROM crewscope.domain_event ORDER BY event_type",
                        String.class));
        assertEquals(
                2,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.outbox_event", Integer.class));
    }

    @Test
    void rollsBackTheEntireConfirmationGraphWhenPublicationFails() {
        Fixture fixture = seedFixture("a07-rollback");
        Conversation conversation = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "A07 rollback");
        conversationRepository.create(conversation);
        TaskIntent ready = taskIntentRepository.create(readyIntent(fixture, conversation));
        OutboxRepository failingOutbox = ignored -> {
            throw new SimulatedMessageOutboxFailure();
        };

        assertThrows(
                SimulatedMessageOutboxFailure.class,
                () -> confirmationService(fixture, conversation, failingOutbox).confirm(
                        messageContext(fixture, "a07-confirm-rollback"),
                        fixture.teamId(),
                        new ConversationIdAndTaskIntentId(conversation.id(), ready.id()),
                        new ConfirmTaskIntentCommand(ready.version())));

        assertEquals(
                TaskIntentStatus.READY,
                taskIntentRepository
                        .findById(fixture.organizationId(), ready.id())
                        .orElseThrow()
                        .status());
        for (String table : List.of(
                "work_item",
                "responsibility_assignment",
                "conversation_work_item_link",
                "domain_event",
                "outbox_event",
                "command_receipt",
                "conversation_event")) {
            assertEquals(
                    0,
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM crewscope." + table, Integer.class));
        }
    }

    @Test
    void serializesConcurrentConfirmationsIntoDistinctNativeWorkItemKeys() throws Exception {
        Fixture fixture = seedFixture("a07-key-race");
        Conversation firstConversation = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "First A07 key");
        Conversation secondConversation = conversation(
                fixture, ConversationId.generate(), BASE_TIME, "Second A07 key");
        conversationRepository.create(firstConversation);
        conversationRepository.create(secondConversation);
        TaskIntent firstIntent =
                taskIntentRepository.create(readyIntent(fixture, firstConversation));
        TaskIntent secondIntent =
                taskIntentRepository.create(readyIntent(fixture, secondConversation));
        TaskIntentConfirmationService firstService =
                confirmationService(fixture, firstConversation, outboxRepository);
        TaskIntentConfirmationService secondService =
                confirmationService(fixture, secondConversation, outboxRepository);
        CountDownLatch readyWriters = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Future<? extends CommandExecution<?>>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                readyWriters.countDown();
                start.await(10, TimeUnit.SECONDS);
                return firstService.confirm(
                        messageContext(fixture, "a07-key-first"),
                        fixture.teamId(),
                        new ConversationIdAndTaskIntentId(
                                firstConversation.id(), firstIntent.id()),
                        new ConfirmTaskIntentCommand(firstIntent.version()));
            }));
            futures.add(executor.submit(() -> {
                readyWriters.countDown();
                start.await(10, TimeUnit.SECONDS);
                return secondService.confirm(
                        messageContext(fixture, "a07-key-second"),
                        fixture.teamId(),
                        new ConversationIdAndTaskIntentId(
                                secondConversation.id(), secondIntent.id()),
                        new ConfirmTaskIntentCommand(secondIntent.version()));
            }));
            assertTrue(readyWriters.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<? extends CommandExecution<?>> future : futures) {
                assertFalse(future.get(30, TimeUnit.SECONDS).replayed());
            }
        } finally {
            executor.shutdownNow();
        }

        String prefix = projectKey(fixture).value();
        assertEquals(
                List.of(prefix + "-1", prefix + "-2"),
                jdbcTemplate.queryForList(
                        "SELECT item_key FROM crewscope.work_item ORDER BY item_key",
                        String.class));
    }

    @Test
    void resolvesConcurrentSessionInitializationToOneCommittedBinding() throws Exception {
        Fixture fixture = seedFixture("session");
        Conversation conversation = conversation(fixture, ConversationId.generate(), BASE_TIME, "Session");
        conversationRepository.create(conversation);
        AgentRuntimeSession candidate = session(fixture, conversation);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Set<AgentRuntimeSessionId> results = java.util.Collections.synchronizedSet(new HashSet<>());

        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        transaction.executeWithoutResult(ignored -> results.add(
                                sessionRepository.initializeIfAbsent(candidate).id()));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(Set.of(candidate.id()), results);
        assertEquals(
                1,
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.agent_runtime_session", Integer.class));
    }

    @Test
    void returnsOnlyScopeClosedBindingCandidatesAndUsesResolverIndex() {
        Fixture fixture = seedFixture("binding");
        ProviderDefinition definition = definitionRepository.create(providerDefinition(fixture));
        ProviderImplementation implementation = implementationRepository.create(
                providerImplementation(fixture, definition));
        ProviderOwner owner = ProviderOwner.organization(fixture.organizationId());
        bindingRepository.create(providerBinding(
                fixture, definition, implementation, owner, ProviderBindingTargetType.WORKSPACE, false));
        bindingRepository.create(providerBinding(
                fixture, definition, implementation, owner, ProviderBindingTargetType.WORK_PROJECT, true));
        CredentialId credentialId = seedCredential(fixture);
        Connection connection = connectionRepository.create(Connection.reconstitute(
                ConnectionId.generate(),
                fixture.organizationId(),
                owner,
                "github-oauth",
                "organization-account",
                credentialId,
                ConnectionStatus.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME)));
        ProviderAccessScope externalAccess = new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"), ProviderResourceScope.allResources());
        ConnectionGrant grant = connectionGrantRepository.create(ConnectionGrant.reconstitute(
                ConnectionGrantId.generate(),
                fixture.organizationId(),
                connection.id(),
                owner,
                owner,
                externalAccess,
                BASE_TIME,
                Optional.empty(),
                ConnectionGrantStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME)));
        ProviderImplementation externalImplementation = implementationRepository.create(
                externalProviderImplementation(fixture, definition));
        bindingRepository.create(externalProviderBinding(
                fixture, definition, externalImplementation, owner, connection, grant));

        List<ProviderBinding> candidates = bindingRepository.findCandidates(new ProviderBindingQuery(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                Optional.of(fixture.workProjectId()),
                owner,
                ProviderType.SOURCE_CODE,
                Optional.empty()));
        List<ProviderBinding> workspaceOnly = bindingRepository.findCandidates(new ProviderBindingQuery(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                Optional.empty(),
                owner,
                ProviderType.SOURCE_CODE,
                Optional.empty()));
        List<ProviderBinding> organizationIdentity = bindingRepository.findCandidates(
                new ProviderBindingQuery(
                        fixture.organizationId(),
                        fixture.teamId(),
                        fixture.workspaceId(),
                        Optional.of(fixture.workProjectId()),
                        owner,
                        ProviderType.SOURCE_CODE,
                        Optional.of(ProviderExecutionIdentity.ORGANIZATION_SERVICE_ACCOUNT)));

        assertEquals(2, candidates.size());
        assertEquals(1, workspaceOnly.size());
        assertEquals(1, organizationIdentity.size());
        assertEquals(
                ProviderExecutionIdentity.ORGANIZATION_SERVICE_ACCOUNT,
                organizationIdentity.get(0).executionIdentity().orElseThrow());
        jdbcTemplate.execute("SET enable_seqscan = off");
        String plan = String.join(
                "\n",
                jdbcTemplate.queryForList(
                        """
                        EXPLAIN SELECT * FROM crewscope.provider_binding
                        WHERE organization_id = ? AND team_id = ? AND workspace_id = ?
                          AND provider_type = 'SOURCE_CODE' AND status = 'ACTIVE'
                          AND owner_type = 'ORGANIZATION' AND owner_id = ?
                        """,
                        String.class,
                        fixture.organizationId().value(),
                        fixture.teamId().value(),
                        fixture.workspaceId().value(),
                        fixture.organizationId().value()));
        // V21 adds an exact historical-reference key whose prefix also closes the
        // organization/team/workspace scope. PostgreSQL may validly prefer either index.
        assertTrue(
                plan.contains("ix_provider_binding_resolver")
                        || plan.contains("uk_provider_binding_action_reference"),
                plan);
    }

    @Test
    void resolvesProjectCandidateAndFailsClosedThroughJpaFacts() {
        Fixture fixture = seedFixture("resolver");
        ProviderDefinition definition = definitionRepository.create(providerDefinition(fixture));
        ProviderImplementation implementation = implementationRepository.create(
                providerImplementation(fixture, definition));
        ProviderOwner owner = ProviderOwner.organization(fixture.organizationId());
        bindingRepository.create(providerBinding(
                fixture,
                definition,
                implementation,
                owner,
                ProviderBindingTargetType.WORKSPACE,
                true));
        ProviderBinding project = bindingRepository.create(providerBinding(
                fixture,
                definition,
                implementation,
                owner,
                ProviderBindingTargetType.WORK_PROJECT,
                false));
        ProviderBindingResolver resolver = new ProviderBindingResolver(
                bindingRepository,
                definitionRepository,
                implementationRepository,
                connectionRepository,
                connectionGrantRepository,
                () -> BASE_TIME);
        ProviderBindingResolutionRequest request = new ProviderBindingResolutionRequest(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                Optional.of(fixture.workProjectId()),
                owner,
                ProviderType.SOURCE_CODE,
                Optional.empty(),
                new ProviderAccessScope(
                        ProviderCapabilities.of("repository.read"),
                        ProviderResourceScope.allResources()),
                Optional.empty(),
                Optional.empty());

        ProviderBindingResolution resolved = resolver.resolve(request);
        ProviderBinding secondProject = bindingRepository.create(providerBinding(
                fixture,
                definition,
                implementation,
                owner,
                ProviderBindingTargetType.WORK_PROJECT,
                false));
        ProviderBindingResolution ambiguous = resolver.resolve(request);
        definitionRepository.update(definition.disable(0, fixture.ownerPrincipal(), BASE_TIME));
        ProviderBindingResolution stale = resolver.resolve(request);

        assertEquals(ProviderBindingResolutionStatus.RESOLVED, resolved.status());
        assertEquals(ProviderBindingResolutionLevel.WORK_PROJECT, resolved.level());
        assertEquals(project.id(), resolved.candidate().orElseThrow().binding().id());
        assertEquals(ProviderBindingResolutionStatus.AMBIGUOUS, ambiguous.status());
        assertEquals(
                Set.of(project.id(), secondProject.id()),
                Set.copyOf(ambiguous.ambiguousBindingIds()));
        assertEquals(ProviderBindingResolutionStatus.NOT_FOUND, stale.status());
        assertEquals(ProviderBindingResolutionLevel.WORK_PROJECT, stale.level());
    }

    @Test
    void roundTripsAndVersionChecksConnectionsAndCapabilityGrants() {
        Fixture fixture = seedFixture("connection");
        CredentialId credentialId = seedCredential(fixture);
        ProviderOwner owner = ProviderOwner.organization(fixture.organizationId());
        Connection connection = Connection.reconstitute(
                ConnectionId.generate(),
                fixture.organizationId(),
                owner,
                "github-oauth",
                "account-123",
                credentialId,
                ConnectionStatus.ACTIVE,
                Optional.empty(),
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
        Connection committedConnection = connectionRepository.create(connection);
        ProviderAccessScope access = new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"),
                ProviderResourceScope.of("repository:crewscope"));
        ConnectionGrant grant = ConnectionGrant.reconstitute(
                ConnectionGrantId.generate(),
                fixture.organizationId(),
                connection.id(),
                owner,
                owner,
                access,
                BASE_TIME,
                Optional.empty(),
                ConnectionGrantStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
        ConnectionGrant committedGrant = connectionGrantRepository.create(grant);

        assertEquals(owner, connectionRepository.findByOwner(owner).get(0).owner());
        assertEquals(
                access,
                connectionGrantRepository
                        .findByConnectionAndGrantee(connection.id(), owner)
                        .get(0)
                        .grantedAccess());
        assertEquals(
                ConnectionStatus.REVOKED,
                connectionRepository
                        .update(committedConnection.revoke(
                                0,
                                fixture.ownerPrincipal(),
                                "credential removed",
                                UtcTimestamp.parse("2026-08-08T12:20:00Z")))
                        .status());
        assertEquals(
                ConnectionGrantStatus.REVOKED,
                connectionGrantRepository
                        .update(committedGrant.revoke(
                                0,
                                fixture.ownerPrincipal(),
                                "access removed",
                                UtcTimestamp.parse("2026-08-08T12:21:00Z")))
                        .status());
    }

    private TaskIntentConfirmationService confirmationService(
            Fixture fixture, Conversation conversation, OutboxRepository selectedOutbox) {
        TaskIntentApplicationService reviewService =
                taskIntentService(fixture, conversation, selectedOutbox);
        WorkProject project = WorkProject.reconstitute(
                fixture.workProjectId(),
                new WorkProjectScope(
                        fixture.organizationId(), fixture.teamId(), fixture.workspaceId()),
                projectKey(fixture),
                "A07 project",
                WorkProjectStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
        WorkItemAccessPolicy accessPolicy = mock(WorkItemAccessPolicy.class);
        when(accessPolicy.requireCreatePermission(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(fixture.organizationId()),
                        org.mockito.ArgumentMatchers.eq(fixture.teamId()),
                        org.mockito.ArgumentMatchers.eq(fixture.workProjectId()),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(project);
        Team team = Team.create(
                fixture.teamId(),
                fixture.organizationId(),
                "A07 Team",
                fixture.ownerMemberId(),
                fixture.workspaceId(),
                fixture.ownerPrincipalId(),
                BASE_TIME);
        TeamRepository teamRepository = mock(TeamRepository.class);
        when(teamRepository.findById(fixture.organizationId(), fixture.teamId()))
                .thenReturn(Optional.of(team));
        TeamMember member = team.joinMember(
                fixture.ownerMemberId(),
                fixture.ownerPrincipal(),
                TeamJoinMethod.BOOTSTRAP,
                BASE_TIME);
        TeamMembershipQuery membershipQuery = mock(TeamMembershipQuery.class);
        when(membershipQuery.findByTeam(fixture.organizationId(), fixture.teamId()))
                .thenReturn(List.of(member));
        PrincipalRepository principalRepository = mock(PrincipalRepository.class);
        when(principalRepository.findById(
                        fixture.organizationId(), fixture.ownerPrincipalId()))
                .thenReturn(Optional.of(fixture.ownerPrincipal()));
        BuiltInProviderRegistration registration = new BuiltInProviderRegistration(
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
        ProviderBinding binding = mock(ProviderBinding.class);
        ProviderDefinition definition = mock(ProviderDefinition.class);
        ProviderImplementation implementation = mock(ProviderImplementation.class);
        when(binding.id()).thenReturn(ProviderBindingId.generate());
        when(definition.id()).thenReturn(registration.definitionId(fixture.organizationId()));
        when(implementation.id())
                .thenReturn(registration.implementationId(fixture.organizationId()));
        ProviderBindingCandidate candidate = new ProviderBindingCandidate(
                binding,
                definition,
                implementation,
                Optional.empty(),
                Optional.empty(),
                registration.workspaceAccess(fixture.workspaceId()));
        ProviderBindingResolver resolver = mock(ProviderBindingResolver.class);
        when(resolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ProviderBindingResolution.resolved(
                        ProviderBindingResolutionLevel.WORKSPACE, candidate));
        GateReviewerPolicyProvider reviewerPolicy =
                ignored -> ReviewerEligibilityPolicy.strict();
        return new TaskIntentConfirmationService(
                reviewService,
                taskIntentRepository,
                conversationRepository,
                conversationWorkItemLinkRepository,
                workProjectRepository,
                workItemRepository,
                accessPolicy,
                teamRepository,
                membershipQuery,
                principalRepository,
                responsibilityAssignmentRepository,
                reviewerPolicy,
                registration,
                resolver,
                domainEventStore,
                conversationEventRepository,
                selectedOutbox,
                commandReceiptStore,
                transactionExecutor,
                TimeProvider.from(Clock.fixed(
                        Instant.parse("2026-08-08T12:10:00Z"), ZoneOffset.UTC)));
    }

    private WorkProjectKey projectKey(Fixture fixture) {
        return new WorkProjectKey(jdbcTemplate.queryForObject(
                "SELECT project_key FROM crewscope.work_project WHERE id = ?",
                String.class,
                fixture.workProjectId().value()));
    }

    private TaskIntentApplicationService taskIntentService(
            Fixture fixture, Conversation conversation, OutboxRepository selectedOutbox) {
        ConversationApplicationService conversationService =
                mock(ConversationApplicationService.class);
        when(conversationService.get(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(fixture.organizationId()),
                        org.mockito.ArgumentMatchers.eq(fixture.teamId()),
                        org.mockito.ArgumentMatchers.eq(conversation.id())))
                .thenReturn(new ConversationDetails(conversation, List.of()));
        Team team = Team.create(
                fixture.teamId(),
                fixture.organizationId(),
                "Intent Team",
                fixture.ownerMemberId(),
                fixture.workspaceId(),
                fixture.ownerPrincipalId(),
                BASE_TIME);
        TeamMember member = team.joinMember(
                fixture.ownerMemberId(),
                fixture.ownerPrincipal(),
                TeamJoinMethod.BOOTSTRAP,
                BASE_TIME);
        TeamMembershipQuery membershipQuery = mock(TeamMembershipQuery.class);
        when(membershipQuery.findByTeam(fixture.organizationId(), fixture.teamId()))
                .thenReturn(List.of(member));
        Principal agent = Principal.create(
                fixture.agentPrincipalId(),
                PrincipalScope.team(fixture.organizationId(), fixture.teamId()),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(fixture.ownerPrincipalId()),
                "Personal Agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                BASE_TIME);
        PrincipalRepository principalRepository = mock(PrincipalRepository.class);
        when(principalRepository.findById(
                        fixture.organizationId(), fixture.ownerPrincipalId()))
                .thenReturn(Optional.of(fixture.ownerPrincipal()));
        when(principalRepository.findById(
                        fixture.organizationId(), fixture.agentPrincipalId()))
                .thenReturn(Optional.of(agent));
        WorkProject project = WorkProject.reconstitute(
                fixture.workProjectId(),
                new WorkProjectScope(
                        fixture.organizationId(), fixture.teamId(), fixture.workspaceId()),
                new WorkProjectKey("INTENT"),
                "Intent project",
                WorkProjectStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
        WorkProjectRepository projectRepository = mock(WorkProjectRepository.class);
        when(projectRepository.findById(
                        fixture.organizationId(), fixture.workProjectId()))
                .thenReturn(Optional.of(project));
        return new TaskIntentApplicationService(
                conversationService,
                conversationRepository,
                participantRepository,
                conversationEventRepository,
                taskIntentRepository,
                projectRepository,
                membershipQuery,
                principalRepository,
                domainEventStore,
                selectedOutbox,
                commandReceiptStore,
                transactionExecutor,
                TimeProvider.from(Clock.fixed(
                        Instant.parse("2026-08-08T12:05:00Z"), ZoneOffset.UTC)));
    }

    private ConversationApplicationService conversationService(
            Fixture fixture, OutboxRepository selectedOutbox) {
        Team team =
                Team.create(
                        fixture.teamId(),
                        fixture.organizationId(),
                        "Message Team",
                        fixture.ownerMemberId(),
                        fixture.workspaceId(),
                        fixture.ownerPrincipalId(),
                        BASE_TIME);
        TeamMember member =
                team.joinMember(
                        fixture.ownerMemberId(),
                        fixture.ownerPrincipal(),
                        TeamJoinMethod.BOOTSTRAP,
                        BASE_TIME);
        TeamRepository teamRepository = mock(TeamRepository.class);
        when(teamRepository.findUninitializedById(fixture.organizationId(), fixture.teamId()))
                .thenReturn(Optional.empty());
        when(teamRepository.findById(fixture.organizationId(), fixture.teamId()))
                .thenReturn(Optional.of(team));
        TeamMembershipQuery membershipQuery = mock(TeamMembershipQuery.class);
        when(membershipQuery.findByTeam(fixture.organizationId(), fixture.teamId()))
                .thenReturn(List.of(member));
        TimeProvider fixedTime =
                TimeProvider.from(
                        Clock.fixed(
                                Instant.parse("2026-08-08T12:05:00Z"), ZoneOffset.UTC));
        Principal agent = Principal.create(
                fixture.agentPrincipalId(),
                PrincipalScope.team(fixture.organizationId(), fixture.teamId()),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(fixture.ownerPrincipalId()),
                "Personal Agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                BASE_TIME);
        PrincipalRepository principalRepository = mock(PrincipalRepository.class);
        when(principalRepository.findById(
                        fixture.organizationId(), fixture.ownerPrincipalId()))
                .thenReturn(Optional.of(fixture.ownerPrincipal()));
        when(principalRepository.findById(
                        fixture.organizationId(), fixture.agentPrincipalId()))
                .thenReturn(Optional.of(agent));
        return new ConversationApplicationService(
                conversationRepository,
                participantRepository,
                messageRepository,
                conversationEventRepository,
                teamRepository,
                mock(WorkspaceRepository.class),
                membershipQuery,
                principalRepository,
                mock(AgentProfileRepository.class),
                mock(TeamRoleRepository.class),
                mock(MemberRoleRepository.class),
                domainEventStore,
                selectedOutbox,
                commandReceiptStore,
                transactionExecutor,
                fixedTime,
                new ConversationVisibilityPolicy());
    }

    private static TeamCommandContext messageContext(Fixture fixture, String key) {
        return new TeamCommandContext(
                new TeamAccessContext(fixture.ownerPrincipal(), false),
                IdempotencyKey.from(key),
                UUID.randomUUID(),
                Optional.empty());
    }

    private Fixture seedFixture(String suffix) {
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        WorkspaceId workspaceId = WorkspaceId.generate();
        WorkProjectId workProjectId = WorkProjectId.generate();
        PrincipalId ownerId = PrincipalId.generate();
        PrincipalId agentId = PrincipalId.generate();
        TeamMemberId memberId = TeamMemberId.generate();
        AgentProfileId profileId = AgentProfileId.generate();
        jdbcTemplate.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                organizationId.value(),
                "Organization " + suffix);
        jdbcTemplate.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                teamId.value(),
                organizationId.value(),
                "Team " + suffix);
        jdbcTemplate.update(
                "INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status) VALUES (?, ?, 'USER', ?, 'ACTIVE')",
                ownerId.value(),
                organizationId.value(),
                "Owner " + suffix);
        jdbcTemplate.update(
                "INSERT INTO crewscope.principal (id, organization_id, team_id, principal_type, owner_principal_id, display_name, visibility, status) VALUES (?, ?, ?, 'PERSONAL_AGENT', ?, ?, 'PRIVATE', 'ACTIVE')",
                agentId.value(),
                organizationId.value(),
                teamId.value(),
                ownerId.value(),
                "Agent " + suffix);
        jdbcTemplate.update(
                "INSERT INTO crewscope.team_member (id, organization_id, team_id, user_principal_id, status, join_method, joined_at) VALUES (?, ?, ?, ?, 'ACTIVE', 'BOOTSTRAP', ?)",
                memberId.value(),
                organizationId.value(),
                teamId.value(),
                ownerId.value(),
                Timestamp.from(BASE_TIME.value()));
        jdbcTemplate.update(
                "INSERT INTO crewscope.workspace (id, organization_id, team_id, workspace_type, name, status, owner_principal_id) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE', ?)",
                workspaceId.value(),
                organizationId.value(),
                teamId.value(),
                "Workspace " + suffix,
                ownerId.value());
        jdbcTemplate.update(
                "INSERT INTO crewscope.agent_profile (id, organization_id, team_id, workspace_id, agent_principal_id, owner_member_id, profile_type, default_profile, status, created_by_principal_id, updated_by_principal_id) VALUES (?, ?, ?, ?, ?, ?, 'PERSONAL', TRUE, 'ACTIVE', ?, ?)",
                profileId.value(),
                organizationId.value(),
                teamId.value(),
                workspaceId.value(),
                agentId.value(),
                memberId.value(),
                ownerId.value(),
                ownerId.value());
        jdbcTemplate.update(
                "INSERT INTO crewscope.work_project (id, organization_id, team_id, workspace_id, project_key, name, created_by_principal_id, updated_by_principal_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                workProjectId.value(),
                organizationId.value(),
                teamId.value(),
                workspaceId.value(),
                suffix.replaceAll("[^A-Za-z0-9]", "").toUpperCase().substring(0, Math.min(10, suffix.replaceAll("[^A-Za-z0-9]", "").length())),
                "Project " + suffix,
                ownerId.value(),
                ownerId.value());
        jdbcTemplate.update(
                "UPDATE crewscope.organization SET created_by_principal_id = ?, updated_by_principal_id = ? WHERE id = ?",
                ownerId.value(),
                ownerId.value(),
                organizationId.value());
        Principal owner = Principal.create(
                ownerId,
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Owner " + suffix,
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                BASE_TIME);
        return new Fixture(
                organizationId,
                teamId,
                workspaceId,
                workProjectId,
                ownerId,
                agentId,
                memberId,
                profileId,
                owner);
    }

    private static Conversation conversation(
            Fixture fixture, ConversationId id, UtcTimestamp time, String title) {
        return Conversation.reconstitute(
                id,
                fixture.scope(),
                fixture.ownerMemberId(),
                fixture.ownerPrincipalId(),
                fixture.agentPrincipalId(),
                title,
                ConversationVisibility.PRIVATE,
                ConversationStatus.ACTIVE,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), time));
    }

    private static ConversationParticipant participant(Fixture fixture, Conversation conversation) {
        return ConversationParticipant.reconstitute(
                ConversationParticipantId.forPrincipal(
                        conversation.id(), fixture.ownerPrincipalId()),
                fixture.scope(),
                conversation.id(),
                fixture.ownerPrincipalId(),
                Optional.of(fixture.ownerMemberId()),
                ConversationParticipantRole.OWNER,
                ConversationParticipantStatus.ACTIVE,
                fixture.ownerPrincipalId(),
                BASE_TIME,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private static ConversationParticipant agentParticipant(
            Fixture fixture, Conversation conversation) {
        return ConversationParticipant.reconstitute(
                ConversationParticipantId.forPrincipal(
                        conversation.id(), fixture.agentPrincipalId()),
                fixture.scope(),
                conversation.id(),
                fixture.agentPrincipalId(),
                Optional.empty(),
                ConversationParticipantRole.AGENT,
                ConversationParticipantStatus.ACTIVE,
                fixture.ownerPrincipalId(),
                BASE_TIME,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private static TaskIntent readyIntent(Fixture fixture, Conversation conversation) {
        TaskIntentResponsibility owner = new TaskIntentResponsibility(
                ResponsibilityRole.OWNER,
                fixture.ownerPrincipalId(),
                PrincipalType.USER,
                Optional.of(fixture.ownerMemberId()));
        TaskIntentProposal proposal = new TaskIntentProposal(
                fixture.workItemScope(),
                "Implement persisted intent",
                List.of("One WorkItem is created"),
                owner,
                Optional.empty(),
                Optional.empty());
        return TaskIntent.reconstitute(
                TaskIntentId.generate(),
                fixture.scope(),
                conversation.id(),
                fixture.agentPrincipalId(),
                1,
                proposal,
                TaskIntentStatus.READY,
                Optional.empty(),
                0,
                AuditMetadata.createdBy(fixture.agentPrincipalId(), BASE_TIME));
    }

    private static TaskIntent confirmedIntent(TaskIntent ready, PrincipalId actorId) {
        UtcTimestamp time = UtcTimestamp.parse("2026-08-08T12:10:00Z");
        return TaskIntent.reconstitute(
                ready.id(),
                ready.scope(),
                ready.conversationId(),
                ready.proposedByPrincipalId(),
                ready.proposalRevision(),
                ready.proposal(),
                TaskIntentStatus.CONFIRMED,
                Optional.of(TaskIntentDecision.confirmed(actorId, time)),
                ready.version() + 1,
                ready.audit().modifiedBy(actorId, time));
    }

    private WorkItemId seedWorkItem(Fixture fixture, String key) {
        WorkItemId id = WorkItemId.generate();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.work_item (
                    id, organization_id, team_id, workspace_id, project_id,
                    item_key, item_type, title, status, priority, source_provider,
                    created_by_principal_id, updated_by_principal_id
                ) VALUES (?, ?, ?, ?, ?, ?, 'TASK', ?, 'BACKLOG', 'MEDIUM', 'CREWSCOPE', ?, ?)
                """,
                id.value(),
                fixture.organizationId().value(),
                fixture.teamId().value(),
                fixture.workspaceId().value(),
                fixture.workProjectId().value(),
                key,
                "Work item " + key,
                fixture.ownerPrincipalId().value(),
                fixture.ownerPrincipalId().value());
        return id;
    }

    private CredentialId seedCredential(Fixture fixture) {
        CredentialId id = CredentialId.generate();
        jdbcTemplate.update(
                """
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type,
                    ciphertext, nonce, authentication_tag,
                    key_id, algorithm, aad_version, status
                ) VALUES (?, ?, 'ORGANIZATION', ?, ?, 'github', 'OAUTH_TOKEN',
                          ?, ?, ?, 'test-key', 'AES-256-GCM', 'v1', 'ACTIVE')
                """,
                id.value(),
                fixture.organizationId().value(),
                fixture.organizationId().value(),
                "github-" + id,
                new byte[] {1},
                new byte[12],
                new byte[16]);
        return id;
    }

    private static AgentRuntimeSession session(Fixture fixture, Conversation conversation) {
        AgentRuntimeSessionId id = AgentRuntimeSessionId.forPersonalConversation(
                conversation.id(), fixture.ownerMemberId(), fixture.agentPrincipalId());
        return AgentRuntimeSession.reconstitute(
                id,
                fixture.scope(),
                conversation.id(),
                fixture.ownerMemberId(),
                fixture.ownerPrincipalId(),
                fixture.agentPrincipalId(),
                fixture.agentProfileId(),
                0,
                AgentScopeSessionKey.forPersonalConversation(
                        fixture.organizationId(),
                        fixture.ownerMemberId(),
                        fixture.agentPrincipalId(),
                        conversation.id(),
                        id),
                AgentRuntimeStateReference.forSession(id),
                AgentRuntimeSessionStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private static ProviderDefinition providerDefinition(Fixture fixture) {
        return ProviderDefinition.reconstitute(
                ProviderDefinitionId.generate(),
                fixture.organizationId(),
                "github",
                ProviderType.SOURCE_CODE,
                "1.0",
                "GitHub",
                ProviderCapabilities.of("repository.read"),
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private static ProviderImplementation providerImplementation(
            Fixture fixture, ProviderDefinition definition) {
        return ProviderImplementation.reconstitute(
                ProviderImplementationId.generate(),
                fixture.organizationId(),
                definition.id(),
                definition.type(),
                definition.interfaceVersion(),
                "github-native",
                "1.0",
                ProviderCapabilities.of("repository.read"),
                ProviderConnectionRequirement.NONE,
                Optional.empty(),
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private static ProviderImplementation externalProviderImplementation(
            Fixture fixture, ProviderDefinition definition) {
        return ProviderImplementation.reconstitute(
                ProviderImplementationId.generate(),
                fixture.organizationId(),
                definition.id(),
                definition.type(),
                definition.interfaceVersion(),
                "github-oauth",
                "1.0",
                ProviderCapabilities.of("repository.read"),
                ProviderConnectionRequirement.REQUIRED,
                Optional.of("github-oauth"),
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private static ProviderBinding providerBinding(
            Fixture fixture,
            ProviderDefinition definition,
            ProviderImplementation implementation,
            ProviderOwner owner,
            ProviderBindingTargetType targetType,
            boolean defaultUsage) {
        ProviderBindingTarget target = new ProviderBindingTarget(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                targetType,
                targetType == ProviderBindingTargetType.WORK_PROJECT
                        ? Optional.of(fixture.workProjectId())
                        : Optional.empty());
        ProviderAccessScope access = new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"), ProviderResourceScope.allResources());
        return ProviderBinding.reconstitute(
                ProviderBindingId.generate(),
                fixture.organizationId(),
                target,
                owner,
                definition.id(),
                definition.version(),
                definition.type(),
                implementation.id(),
                implementation.version(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                access,
                defaultUsage,
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private static ProviderBinding externalProviderBinding(
            Fixture fixture,
            ProviderDefinition definition,
            ProviderImplementation implementation,
            ProviderOwner owner,
            Connection connection,
            ConnectionGrant grant) {
        ProviderBindingTarget target = new ProviderBindingTarget(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                ProviderBindingTargetType.WORKSPACE,
                Optional.empty());
        ProviderAccessScope access = new ProviderAccessScope(
                ProviderCapabilities.of("repository.read"), ProviderResourceScope.allResources());
        return ProviderBinding.reconstitute(
                ProviderBindingId.generate(),
                fixture.organizationId(),
                target,
                owner,
                definition.id(),
                definition.version(),
                definition.type(),
                implementation.id(),
                implementation.version(),
                Optional.of(connection.id()),
                Optional.of(connection.version()),
                Optional.of(grant.id()),
                Optional.of(grant.version()),
                Optional.of(ProviderExecutionIdentity.ORGANIZATION_SERVICE_ACCOUNT),
                access,
                false,
                ProviderRegistrationStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.ownerPrincipalId(), BASE_TIME));
    }

    private record Fixture(
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            WorkProjectId workProjectId,
            PrincipalId ownerPrincipalId,
            PrincipalId agentPrincipalId,
            TeamMemberId ownerMemberId,
            AgentProfileId agentProfileId,
            Principal ownerPrincipal) {
        ConversationScope scope() {
            return new ConversationScope(organizationId, teamId, workspaceId);
        }

        WorkItemScope workItemScope() {
            return new WorkItemScope(organizationId, teamId, workspaceId, workProjectId);
        }
    }

    private static final class SimulatedMessageOutboxFailure extends RuntimeException {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "io.crewscope.infrastructure.persistence")
    @Import({
        JpaConversationRepositoryAdapter.class,
        JdbcConversationEventRepository.class,
        JpaAgentRuntimeSessionRepositoryAdapter.class,
        ConversationPersistenceMapper.class,
        JdbcDomainEventStore.class,
        JdbcOutboxRepository.class,
        JdbcCommandReceiptStore.class,
        SpringTransactionExecutor.class,
        JpaProviderRepositoryAdapter.class,
        ProviderPersistenceMapper.class,
        JpaWorkProjectRepositoryAdapter.class,
        WorkPersistenceMapper.class,
        JpaWorkItemRepositoryAdapter.class,
        WorkItemEntityMapper.class,
        JpaResponsibilityAssignmentRepositoryAdapter.class,
        ResponsibilityPersistenceMapper.class
    })
    static class TestApplication {}
}
