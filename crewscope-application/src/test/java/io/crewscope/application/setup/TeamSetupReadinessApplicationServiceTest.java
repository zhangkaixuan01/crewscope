package io.crewscope.application.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamPermission;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeamSetupReadinessApplicationServiceTest {

    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final TeamId TEAM = TeamId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-01T12:00:00Z");

    private TeamSetupReadinessApplicationService service;
    private TeamAccessContext access;
    private WorkItemAccessPolicy accessPolicy;
    private Team team;
    private TeamMember member;
    private ConnectionRepository connections;
    private RuntimeObservationService runtimeObservation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accessPolicy = mock(WorkItemAccessPolicy.class);
        TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
        AgentProfileRepository profiles = mock(AgentProfileRepository.class);
        AgentConfigurationRepository configurations = mock(AgentConfigurationRepository.class);
        AgentModelDefaultRepository defaults = mock(AgentModelDefaultRepository.class);
        ModelConnectionRepository modelConnections = mock(ModelConnectionRepository.class);
        ModelCatalogEntryRepository catalogs = mock(ModelCatalogEntryRepository.class);
        ModelProviderDefinitionRepository providers = mock(ModelProviderDefinitionRepository.class);
        WorkProjectRepository projects = mock(WorkProjectRepository.class);
        RepositoryBindingRepository bindings = mock(RepositoryBindingRepository.class);
        connections = mock(ConnectionRepository.class);
        GitHubProviderRepository github = mock(GitHubProviderRepository.class);
        runtimeObservation = mock(RuntimeObservationService.class);
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        when(transactions.required(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(0)).get());

        Principal actor = mock(Principal.class);
        when(actor.id()).thenReturn(ACTOR);
        access = new TeamAccessContext(actor, false);
        team = mock(Team.class);
        when(team.id()).thenReturn(TEAM);
        when(team.organizationId()).thenReturn(ORGANIZATION);
        when(team.status()).thenReturn(io.crewscope.domain.team.TeamStatus.ACTIVE);
        member = mock(TeamMember.class);
        when(member.id()).thenReturn(io.crewscope.domain.team.TeamMemberId.generate());
        when(member.userPrincipalId()).thenReturn(ACTOR);
        when(member.status()).thenReturn(TeamMemberStatus.ACTIVE);
        when(accessPolicy.requireVisibleTeam(access, ORGANIZATION, TEAM)).thenReturn(team);
        doNothing().when(accessPolicy).requireTeamPermission(
                any(), any(), any(), any(), any(), any());
        when(memberships.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of(member));
        when(profiles.findByTeam(ORGANIZATION, TEAM, 0, 200)).thenReturn(List.of());
        when(projects.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of());
        when(connections.findByOwner(any())).thenReturn(List.of());
        when(runtimeObservation.summary(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("runtime is unavailable"));

        service = new TeamSetupReadinessApplicationService(
                accessPolicy,
                memberships,
                profiles,
                configurations,
                defaults,
                modelConnections,
                catalogs,
                providers,
                projects,
                bindings,
                connections,
                github,
                runtimeObservation,
                transactions,
                () -> NOW);
    }

    @Test
    void returnsClosedCapabilityStatesWithoutSensitiveFields() {
        TeamSetupReadinessView result = service.get(
                access, ORGANIZATION, TEAM, new RuntimeEnvironment("development"));

        assertEquals(6, result.capabilities().size());
        assertFalse(result.requiredReady());
        assertNotNull(result.snapshotVersion());
        TeamSetupReadinessItem personal = result.capabilities().get(0);
        assertEquals(TeamSetupCapability.PERSONAL_CONVERSATION, personal.capability());
        assertEquals(TeamSetupReadinessStatus.ACTION_REQUIRED, personal.status());
        assertEquals("PERSONAL_AGENT_CONFIGURATION_REQUIRED", personal.reasonCode());
        assertTrue(personal.canConfigure());
        assertEquals(Optional.of("OPEN_AGENT_SETTINGS"), personal.actionKey());
        result.capabilities().forEach(item -> {
            assertTrue(item.status() != TeamSetupReadinessStatus.READY);
            assertTrue(item.reasonCode().matches("[A-Z][A-Z0-9_]{2,63}"));
            assertTrue(item.actionKey().isEmpty() || item.canConfigure());
        });
    }

    @Test
    void hidesConfigurationActionsFromMembersWithoutTeamPermission() {
        org.mockito.Mockito.doThrow(new PolicyDeniedException("configure Team setup"))
                .when(accessPolicy).requireTeamPermission(
                        any(), any(), any(), any(), any(), any());

        TeamSetupReadinessView result = service.get(
                access, ORGANIZATION, TEAM, new RuntimeEnvironment("development"));

        assertEquals(TeamSetupReadinessStatus.BLOCKED, result.capabilities().get(0).status());
        assertFalse(result.capabilities().get(0).canConfigure());
        assertTrue(result.capabilities().get(0).actionKey().isEmpty());
    }
}
