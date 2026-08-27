package io.crewscope.server.config.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.crewscope.agentscope.teamobserver.TeamObserverModelFactory;
import io.crewscope.agentscope.teamobserver.TeamObserverRuntime;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentExecutionConfigurationService;
import io.crewscope.application.agent.AgentModelGovernance;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Proves the production execution adapter cannot discover USER-owned model connections. */
class AgentScopeTeamObserverExecutionAdapterM6A05Test {

    @Test
    void modelCandidateSetContainsOnlyTeamAndOrganizationOwners() {
        OrganizationId organizationId = OrganizationId.generate();
        UtcTimestamp now = UtcTimestamp.parse("2026-08-27T06:00:00Z");
        Principal owner = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                now);
        TeamInitialization initialization = TeamInitialization.create(owner, "Platform", now);
        ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
        ModelConnection teamConnection = connection("00000000-0000-0000-0000-000000000001");
        ModelConnection organizationConnection =
                connection("00000000-0000-0000-0000-000000000002");
        ModelConnectionOwner teamOwner = ModelConnectionOwner.team(initialization.team());
        ModelConnectionOwner organizationOwner = ModelConnectionOwner.organization(organizationId);
        when(connections.findByOwner(teamOwner)).thenReturn(List.of(teamConnection));
        when(connections.findByOwner(organizationOwner)).thenReturn(List.of(organizationConnection));

        AgentScopeTeamObserverExecutionAdapter adapter = new AgentScopeTeamObserverExecutionAdapter(
                mock(TeamRepository.class),
                mock(AgentProfileRepository.class),
                mock(AgentTemplateRepository.class),
                mock(AgentConfigurationRepository.class),
                connections,
                mock(AgentModelGovernance.class),
                mock(AgentExecutionConfigurationService.class),
                mock(TeamObserverModelFactory.class),
                mock(TeamObserverRuntime.class),
                () -> now);

        assertEquals(
                List.of(teamConnection, organizationConnection),
                adapter.usableConnections(initialization.team()));
        verify(connections).findByOwner(teamOwner);
        verify(connections).findByOwner(organizationOwner);
        verifyNoMoreInteractions(connections);
    }

    private static ModelConnection connection(String id) {
        ModelConnection value = mock(ModelConnection.class);
        when(value.id()).thenReturn(new ModelConnectionId(java.util.UUID.fromString(id)));
        return value;
    }
}
