package io.crewscope.application.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurationHash;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.conversation.AgentRuntimeSession;
import io.crewscope.domain.conversation.PersonalConversationInitialization;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M5-A03 owner, safe-point, Revision, optimistic concurrency and Receipt tests. */
class ConversationConfigurationRefreshServiceM5A03Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T16:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal owner = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Conversation owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization team = TeamInitialization.create(owner, "Refresh team", NOW);
    private final PersonalConversationInitialization conversation =
            PersonalConversationInitialization.start(
                    io.crewscope.domain.conversation.ConversationId.generate(),
                    team.defaultWorkspace(),
                    team.ownerMember(),
                    owner,
                    team.ownerPersonalAgent(),
                    "Pinned configuration",
                    io.crewscope.domain.conversation.ConversationVisibility.PRIVATE,
                    NOW);

    private AgentRuntimeSessionRepository sessions;
    private CommandReceiptStore receipts;
    private DomainEventStore events;
    private OutboxRepository outbox;
    private AtomicBoolean safe;
    private ConversationConfigurationRefreshService service;
    private AgentRuntimeSession session;
    private AgentConfigurationVersion current;

    @BeforeEach
    void setUp() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        TeamMemberRepository members = mock(TeamMemberRepository.class);
        PrincipalRepository principals = mock(PrincipalRepository.class);
        AgentProfileRepository profiles = mock(AgentProfileRepository.class);
        AgentConfigurationRepository configurations = mock(AgentConfigurationRepository.class);
        sessions = mock(AgentRuntimeSessionRepository.class);
        receipts = mock(CommandReceiptStore.class);
        events = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);

        AgentConfigurationVersion initial = configuration(1, "a");
        current = configuration(2, "b");
        session = AgentRuntimeSession.initializePersonal(
                conversation.conversation(),
                team.defaultWorkspace(),
                team.ownerMember(),
                owner,
                team.ownerPersonalAgent(),
                Optional.of(initial),
                NOW);

        when(conversations.findById(organizationId, conversation.conversation().id()))
                .thenReturn(Optional.of(conversation.conversation()));
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        when(members.findById(organizationId, team.ownerMember().id()))
                .thenReturn(Optional.of(team.ownerMember()));
        when(principals.findById(
                        organizationId, team.ownerPersonalAgent().agentPrincipal().id()))
                .thenReturn(Optional.of(team.ownerPersonalAgent().agentPrincipal()));
        when(profiles.findActiveDefaultPersonal(organizationId, team.ownerMember().id()))
                .thenReturn(Optional.of(team.ownerPersonalAgent().agentProfile()));
        when(configurations.findCurrent(
                        organizationId, team.ownerPersonalAgent().agentProfile().id()))
                .thenReturn(Optional.of(current));
        when(sessions.findActiveByConversation(
                        organizationId, conversation.conversation().id()))
                .thenReturn(Optional.of(session));
        when(sessions.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(receipts.findCompleted(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());

        safe = new AtomicBoolean(true);
        ConversationConfigurationRefreshGuard guard = (organization, teamId, conversationId) -> {
            if (!safe.get()) {
                throw new PolicyDeniedException("refresh an active Conversation invocation");
            }
        };
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        service = new ConversationConfigurationRefreshService(
                conversations,
                workspaces,
                members,
                principals,
                profiles,
                configurations,
                sessions,
                guard,
                events,
                outbox,
                receipts,
                transactions,
                () -> NOW);
    }

    @Test
    void advancesOnlyThePinnedConfigurationAndWritesTheAtomicEvidenceChain() {
        var result = service.refresh(
                context("m5-a03-refresh"),
                team.team().id(),
                conversation.conversation().id(),
                0);

        AgentRuntimeSession refreshed = result.result().orElseThrow();
        assertEquals(1, refreshed.version());
        assertEquals(
                2,
                refreshed.configurationPin().orElseThrow()
                        .configurationRevision().orElseThrow().value());
        assertEquals(session.agentScopeKey(), refreshed.agentScopeKey());
        assertEquals(session.stateReference(), refreshed.stateReference());
        verify(sessions).update(any());
        verify(events).append(any());
        verify(outbox).enqueue(any());
        verify(receipts).complete(any(), any(), any(), any());
    }

    @Test
    void refusesActiveOrInterruptedWorkBeforeReservation() {
        safe.set(false);

        assertThrows(
                PolicyDeniedException.class,
                () -> service.refresh(
                        context("m5-a03-active"),
                        team.team().id(),
                        conversation.conversation().id(),
                        0));

        verify(receipts, never()).reserve(any());
        verify(sessions, never()).update(any());
    }

    @Test
    void rejectsAStaleSessionVersionWithoutMutatingTheSession() {
        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.refresh(
                        context("m5-a03-stale"),
                        team.team().id(),
                        conversation.conversation().id(),
                        1));

        verify(sessions, never()).update(any());
    }

    @Test
    void replaysACompletedReceiptAfterRecheckingCurrentOwnerFacts() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID());
        when(receipts.findCompleted(any(), any(), any(), any()))
                .thenReturn(Optional.of(receipt));
        safe.set(false);

        var replay = service.refresh(
                context("m5-a03-replay"),
                team.team().id(),
                conversation.conversation().id(),
                0);

        assertTrue(replay.replayed());
        assertEquals(receipt, replay.receipt());
        verify(receipts, never()).reserve(any());
        verify(sessions, never()).update(any());
    }

    private AgentConfigurationVersion configuration(long revision, String hashCharacter) {
        AgentConfigurationVersion value = mock(AgentConfigurationVersion.class);
        when(value.organizationId()).thenReturn(organizationId);
        when(value.agentProfileId()).thenReturn(team.ownerPersonalAgent().agentProfile().id());
        when(value.ownership()).thenReturn(team.ownerPersonalAgent().agentProfile().ownership());
        when(value.templateVersion())
                .thenReturn(team.ownerPersonalAgent().agentProfile().templateVersion());
        when(value.revision()).thenReturn(new AgentConfigurationRevision(revision));
        when(value.configurationHash())
                .thenReturn(new AgentConfigurationHash(hashCharacter.repeat(64)));
        return value;
    }

    private TeamCommandContext context(String key) {
        return new TeamCommandContext(
                new TeamAccessContext(owner, false),
                IdempotencyKey.from(key),
                UUID.randomUUID(),
                Optional.empty());
    }
}
