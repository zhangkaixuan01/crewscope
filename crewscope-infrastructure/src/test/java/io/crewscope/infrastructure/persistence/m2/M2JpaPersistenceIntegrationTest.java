package io.crewscope.infrastructure.persistence.m2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.conversation.AgentRuntimeSessionRepository;
import io.crewscope.application.conversation.ConversationPage;
import io.crewscope.application.conversation.ConversationParticipantRepository;
import io.crewscope.application.conversation.ConversationQuery;
import io.crewscope.application.conversation.ConversationRepository;
import io.crewscope.application.conversation.MessageHistoryQuery;
import io.crewscope.application.conversation.MessagePage;
import io.crewscope.application.conversation.MessageRepository;
import io.crewscope.application.conversation.TaskIntentRepository;
import io.crewscope.application.provider.ConnectionGrantRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.provider.ProviderBindingQuery;
import io.crewscope.application.provider.ProviderBindingRepository;
import io.crewscope.application.provider.ProviderDefinitionRepository;
import io.crewscope.application.provider.ProviderImplementationRepository;
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
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderImplementationId;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.provider.ProviderRegistrationStatus;
import io.crewscope.domain.provider.ProviderResourceScope;
import io.crewscope.domain.provider.ProviderType;
import io.crewscope.domain.responsibility.ResponsibilityRole;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.workitem.WorkItemId;
import io.crewscope.domain.workitem.WorkItemScope;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.infrastructure.persistence.conversation.ConversationPersistenceMapper;
import io.crewscope.infrastructure.persistence.conversation.JpaAgentRuntimeSessionRepositoryAdapter;
import io.crewscope.infrastructure.persistence.conversation.JpaConversationRepositoryAdapter;
import io.crewscope.infrastructure.persistence.provider.JpaProviderRepositoryAdapter;
import io.crewscope.infrastructure.persistence.provider.ProviderPersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    @Autowired private ConversationParticipantRepository participantRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private TaskIntentRepository taskIntentRepository;
    @Autowired private AgentRuntimeSessionRepository sessionRepository;
    @Autowired private ProviderDefinitionRepository definitionRepository;
    @Autowired private ProviderImplementationRepository implementationRepository;
    @Autowired private ProviderBindingRepository bindingRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectionGrantRepository connectionGrantRepository;
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
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1));
        ConversationPage pageTwo = conversationRepository.findPage(new ConversationQuery(
                fixture.organizationId(),
                fixture.teamId(),
                Optional.empty(),
                Optional.empty(),
                pageOne.nextCursor(),
                1));
        MessagePage messageOne = messageRepository.findPage(new MessageHistoryQuery(
                fixture.scope(), first.id(), Optional.empty(), 1));
        MessagePage messageTwo = messageRepository.findPage(new MessageHistoryQuery(
                fixture.scope(), first.id(), messageOne.nextCursor(), 1));

        assertEquals(2, Set.of(pageOne.conversations().get(0).id(), pageTwo.conversations().get(0).id()).size());
        assertEquals(List.of(2L), messageOne.messages().stream().map(value -> value.sequence().value()).toList());
        assertEquals(List.of(1L), messageTwo.messages().stream().map(value -> value.sequence().value()).toList());
        assertTrue(messageRepository
                .findByClientMessageKey(fixture.organizationId(), first.id(), "client-1")
                .isPresent());
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

        List<ProviderBinding> candidates = bindingRepository.findCandidates(new ProviderBindingQuery(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                Optional.of(fixture.workProjectId()),
                owner,
                ProviderType.SOURCE_CODE));
        List<ProviderBinding> workspaceOnly = bindingRepository.findCandidates(new ProviderBindingQuery(
                fixture.organizationId(),
                fixture.teamId(),
                fixture.workspaceId(),
                Optional.empty(),
                owner,
                ProviderType.SOURCE_CODE));

        assertEquals(2, candidates.size());
        assertEquals(1, workspaceOnly.size());
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
        assertTrue(plan.contains("ix_provider_binding_resolver"), plan);
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "io.crewscope.infrastructure.persistence")
    @Import({
        JpaConversationRepositoryAdapter.class,
        JpaAgentRuntimeSessionRepositoryAdapter.class,
        ConversationPersistenceMapper.class,
        JpaProviderRepositoryAdapter.class,
        ProviderPersistenceMapper.class
    })
    static class TestApplication {}
}
