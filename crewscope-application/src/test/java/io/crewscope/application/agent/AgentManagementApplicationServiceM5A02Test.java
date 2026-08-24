package io.crewscope.application.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.workspace.AgentProfile;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** M5-A02 template policy, owner authorization and atomic Agent identity tests. */
class AgentManagementApplicationServiceM5A02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-24T13:00:00Z");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Agent owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Agent team", NOW);
    private final AgentTemplateDefinition coding = template(
            "coding", AgentRuntimeRole.SPECIALIST,
            Set.of(AgentOwnershipType.USER, AgentOwnershipType.TEAM));

    private AgentTemplateRepository templates;
    private AgentProfileRepository profiles;
    private AgentConfigurationRepository configurations;
    private AgentInstanceRepository instances;
    private PrincipalRepository principals;
    private TeamRepository teams;
    private WorkspaceRepository workspaces;
    private TeamMembershipQuery memberships;
    private TeamRoleRepository roles;
    private MemberRoleRepository grants;
    private DomainEventStore events;
    private OutboxRepository outbox;
    private CommandReceiptStore receipts;
    private AgentManagementApplicationService service;

    @BeforeEach
    void setUp() {
        templates = mock(AgentTemplateRepository.class);
        profiles = mock(AgentProfileRepository.class);
        configurations = mock(AgentConfigurationRepository.class);
        instances = mock(AgentInstanceRepository.class);
        principals = mock(PrincipalRepository.class);
        teams = mock(TeamRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        memberships = mock(TeamMembershipQuery.class);
        roles = mock(TeamRoleRepository.class);
        grants = mock(MemberRoleRepository.class);
        events = mock(DomainEventStore.class);
        outbox = mock(OutboxRepository.class);
        receipts = mock(CommandReceiptStore.class);
        when(teams.findUninitializedById(organizationId, initialization.team().id()))
                .thenReturn(Optional.empty());
        when(teams.findById(organizationId, initialization.team().id()))
                .thenReturn(Optional.of(initialization.team()));
        when(workspaces.findById(organizationId, initialization.defaultWorkspace().id()))
                .thenReturn(Optional.of(initialization.defaultWorkspace()));
        when(memberships.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(List.of(initialization.ownerMember()));
        when(roles.findByTeam(organizationId, initialization.team().id()))
                .thenReturn(initialization.builtInRoles());
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of(initialization.ownerRole()));
        when(templates.findByVersion(coding.publisherScope(), coding.templateVersion()))
                .thenReturn(Optional.of(coding));
        when(receipts.reserve(any())).thenReturn(CommandReservation.newlyAcquired());
        when(instances.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(instances.updateLifecycle(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TransactionExecutor transactions = new TransactionExecutor() {
            @Override
            public <T> T required(Supplier<T> operation) {
                return operation.get();
            }
        };
        service = new AgentManagementApplicationService(
                templates,
                profiles,
                configurations,
                instances,
                principals,
                teams,
                workspaces,
                memberships,
                roles,
                grants,
                events,
                outbox,
                receipts,
                transactions,
                () -> NOW);
    }

    @Test
    void createsUserOwnedSpecialistAsOnePrivatePrincipalProfilePair() {
        service.create(context("m5-a02-create"), initialization.team().id(), request("Java Coding"));

        ArgumentCaptor<AgentInstance> captured = ArgumentCaptor.forClass(AgentInstance.class);
        verify(instances).create(captured.capture());
        AgentInstance value = captured.getValue();
        assertEquals(PrincipalType.SPECIALIST_AGENT, value.principal().type());
        assertEquals(PrincipalVisibility.PRIVATE, value.principal().visibility());
        assertEquals(Optional.of(actor.id()), value.principal().ownerPrincipalId());
        assertEquals(AgentOwnershipType.USER, value.profile().ownership().type());
        assertEquals(initialization.ownerMember().id(),
                value.profile().ownership().ownerMemberId().orElseThrow());
        assertEquals(coding.templateVersion(), value.profile().templateVersion());
        assertFalse(value.profile().defaultProfile());
        verify(events).append(any());
        verify(outbox).enqueue(any());
        verify(receipts).complete(any(), any(), any(), any());
    }

    @Test
    void allowsMultipleIndependentSpecialistsForTheSameMemberAndTemplate() {
        service.create(context("m5-a02-first"), initialization.team().id(), request("Backend Coding"));
        service.create(context("m5-a02-second"), initialization.team().id(), request("Frontend Coding"));

        ArgumentCaptor<AgentInstance> captured = ArgumentCaptor.forClass(AgentInstance.class);
        verify(instances, org.mockito.Mockito.times(2)).create(captured.capture());
        assertEquals(2, captured.getAllValues().stream()
                .map(value -> value.profile().id())
                .distinct()
                .count());
    }

    @Test
    void deniesTeamOwnedAgentWithoutCurrentAgentManageGrantBeforeReservation() {
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of());
        CreateAgentRequest teamRequest = new CreateAgentRequest(
                AgentOwnershipType.TEAM,
                coding.publisherScope(),
                coding.templateVersion(),
                "Team Coding");

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(
                        context("m5-a02-denied"), initialization.team().id(), teamRequest));

        verify(receipts, never()).reserve(any());
        verify(instances, never()).create(any());
    }

    @Test
    void catalogReturnsOnlyInstantiableNonPersonalTemplates() {
        AgentTemplateDefinition personal = template(
                "personal-assistant",
                AgentRuntimeRole.PERSONAL_ASSISTANT,
                Set.of(AgentOwnershipType.USER));
        when(templates.findLatestActivePage(
                        AgentTemplatePublisherScope.organization(organizationId), 0, 20))
                .thenReturn(List.of(coding, personal));
        when(templates.findLatestActivePage(
                        AgentTemplatePublisherScope.team(
                                organizationId, initialization.team().id()), 0, 20))
                .thenReturn(List.of());

        List<AgentTemplateDefinition> result = service.listTemplates(
                access(),
                organizationId,
                initialization.team().id(),
                AgentOwnershipType.USER,
                0,
                20);

        assertEquals(List.of(coding), result);
    }

    @Test
    void refusesLifecycleMutationOfTheDefaultPersonalAgent() {
        AgentProfile personal = initialization.ownerPersonalAgent().agentProfile();
        when(profiles.findById(organizationId, personal.id())).thenReturn(Optional.of(personal));

        assertThrows(
                DomainValidationException.class,
                () -> service.disable(
                        context("m5-a02-default-disable"),
                        initialization.team().id(),
                        personal.id(),
                        personal.version()));

        verify(instances, never()).updateLifecycle(any());
    }

    @Test
    void disablesUserOwnedAgentWithSynchronizedPrincipalAndProfileVersions() {
        AgentInstance created = createInstance("Lifecycle Coding");
        when(profiles.findById(organizationId, created.profile().id()))
                .thenReturn(Optional.of(created.profile()));
        when(principals.findById(organizationId, created.principal().id()))
                .thenReturn(Optional.of(created.principal()));

        service.disable(
                context("m5-a02-disable"),
                initialization.team().id(),
                created.profile().id(),
                created.profile().version());

        ArgumentCaptor<AgentInstance> captured = ArgumentCaptor.forClass(AgentInstance.class);
        verify(instances).updateLifecycle(captured.capture());
        assertEquals(PrincipalStatus.DISABLED, captured.getValue().principal().status());
        assertEquals(io.crewscope.domain.workspace.AgentProfileStatus.DISABLED,
                captured.getValue().profile().status());
        assertEquals(1, captured.getValue().principal().version());
        assertEquals(1, captured.getValue().profile().version());
    }

    @Test
    void deniesCrossOwnerDetailAndLifecycleBeforeReadingPrincipalOrReservingCommand() {
        AgentInstance created = createInstance("Private Coding");
        AgentProfile otherOwned = AgentProfile.reconstituteTemplateInstance(
                created.profile().id(),
                created.profile().scope(),
                created.profile().workspaceId(),
                created.profile().agentPrincipalId(),
                io.crewscope.domain.agent.AgentOwnership.user(
                        organizationId,
                        initialization.team().id(),
                        io.crewscope.domain.team.TeamMemberId.generate()),
                created.profile().runtimeRole(),
                created.profile().templateVersion(),
                created.profile().type(),
                false,
                created.profile().status(),
                created.profile().version(),
                created.profile().audit());
        when(profiles.findById(organizationId, otherOwned.id()))
                .thenReturn(Optional.of(otherOwned));

        assertThrows(
                PolicyDeniedException.class,
                () -> service.get(
                        access(), organizationId, initialization.team().id(), otherOwned.id()));
        assertThrows(
                PolicyDeniedException.class,
                () -> service.disable(
                        context("m5-a02-cross-owner"),
                        initialization.team().id(),
                        otherOwned.id(),
                        otherOwned.version()));

        verify(principals, never()).findById(any(), any());
        verify(receipts, never()).reserve(any());
    }

    @Test
    void rechecksCurrentTeamAgentAuthorityBeforeReceiptReplay() {
        when(grants.findByMember(organizationId, initialization.ownerMember().id()))
                .thenReturn(List.of());
        when(receipts.reserve(any())).thenReturn(CommandReservation.replay(
                new io.crewscope.application.command.CommandReceipt(
                        UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID())));
        CreateAgentRequest teamRequest = new CreateAgentRequest(
                AgentOwnershipType.TEAM,
                coding.publisherScope(),
                coding.templateVersion(),
                "Team Coding");

        assertThrows(
                PolicyDeniedException.class,
                () -> service.create(
                        context("m5-a02-replay-revoked"),
                        initialization.team().id(),
                        teamRequest));

        verify(receipts, never()).reserve(any());
    }

    @Test
    void hidesConfigurationHistoryWhenTheRouteTeamDoesNotOwnTheAgent() {
        AgentInstance created = createInstance("Scoped Coding");
        when(profiles.findById(organizationId, created.profile().id()))
                .thenReturn(Optional.of(created.profile()));

        assertThrows(
                AggregateNotFoundException.class,
                () -> service.configurationHistory(
                        access(),
                        organizationId,
                        TeamId.generate(),
                        created.profile().id(),
                        0,
                        20));

        verify(configurations, never()).findPage(any(), any(), any(Integer.class), any(Integer.class));
    }

    private AgentInstance createInstance(String name) {
        Principal principal = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, initialization.team().id()),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(actor.id()),
                name,
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                NOW);
        AgentProfile profile = AgentProfile.createTemplateInstance(
                io.crewscope.domain.workspace.AgentProfileId.generate(),
                initialization.defaultWorkspace(),
                principal,
                io.crewscope.domain.agent.AgentOwnership.user(
                        organizationId,
                        initialization.team().id(),
                        initialization.ownerMember().id()),
                coding,
                false,
                actor.id(),
                NOW);
        return new AgentInstance(principal, profile);
    }

    private CreateAgentRequest request(String name) {
        return new CreateAgentRequest(
                AgentOwnershipType.USER,
                coding.publisherScope(),
                coding.templateVersion(),
                name);
    }

    private TeamCommandContext context(String key) {
        return new TeamCommandContext(
                access(), new IdempotencyKey(key), UUID.randomUUID(), Optional.empty());
    }

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private AgentTemplateDefinition template(
            String key,
            AgentRuntimeRole role,
            Set<AgentOwnershipType> ownershipTypes) {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(organizationId),
                new AgentTemplateKey(key),
                role,
                ownershipTypes,
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability(key + ".execute")),
                        Set.of()),
                AgentTemplatePolicy.define(
                        "Approved template baseline.",
                        Set.of(new AgentToolKey(key + ".inspect")),
                        Set.of(),
                        Optional.empty(),
                        Set.of(AgentConfigurableSlot.DISPLAY_NAME),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                actor.id(),
                NOW);
    }
}
