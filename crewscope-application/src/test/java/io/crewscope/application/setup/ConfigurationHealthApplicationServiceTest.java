package io.crewscope.application.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamStatus;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ConfigurationHealthApplicationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void derivesActionableHealthWithoutPersistingState() {
        OrganizationId organization = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        PrincipalId actorId = PrincipalId.generate();
        Principal actor = mock(Principal.class);
        when(actor.id()).thenReturn(actorId);
        TeamAccessContext access = new TeamAccessContext(actor, false);
        Team team = mock(Team.class);
        when(team.id()).thenReturn(teamId);
        when(team.organizationId()).thenReturn(organization);
        when(team.status()).thenReturn(TeamStatus.ACTIVE);
        TeamMember member = mock(TeamMember.class);
        when(member.userPrincipalId()).thenReturn(actorId);
        when(member.status()).thenReturn(TeamMemberStatus.ACTIVE);
        WorkItemAccessPolicy policy = mock(WorkItemAccessPolicy.class);
        when(policy.requireVisibleTeam(access, organization, teamId)).thenReturn(team);
        TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
        when(memberships.findByTeam(organization, teamId)).thenReturn(List.of(member));
        AgentProfileRepository profiles = mock(AgentProfileRepository.class);
        when(profiles.findVisibleToMember(organization, teamId, member.id(), 0, 200))
                .thenReturn(List.of());
        ModelConnectionRepository modelConnections = mock(ModelConnectionRepository.class);
        when(modelConnections.findByOwner(any())).thenReturn(List.of());
        ConnectionRepository connections = mock(ConnectionRepository.class);
        when(connections.findByOwner(any())).thenReturn(List.of());
        TransactionExecutor transactions = mock(TransactionExecutor.class);
        when(transactions.required(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(0)).get());
        ConfigurationHealthApplicationService service = new ConfigurationHealthApplicationService(
                policy, memberships, profiles, mock(AgentConfigurationRepository.class),
                modelConnections, connections, mock(CredentialStore.class), transactions,
                () -> UtcTimestamp.parse("2026-09-13T12:00:00Z"));

        ConfigurationHealthView result = service.get(
                access, organization, teamId, new RuntimeEnvironment("development"));

        assertEquals(ConfigurationHealthStatus.ACTION_REQUIRED, result.overallStatus());
        assertEquals(4, result.items().size());
        assertEquals("AGENT_CONFIGURATION_REQUIRED", result.items().get(0).reasonCode());
        assertEquals("MODEL_CONNECTION_REQUIRED", result.items().get(1).reasonCode());
    }
}
