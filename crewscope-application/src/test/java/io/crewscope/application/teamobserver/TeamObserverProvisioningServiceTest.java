package io.crewscope.application.teamobserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationDraft;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.model.SelectableModelOption;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Startup and first-use repair coverage for Teams created after the V28 backfill. */
class TeamObserverProvisioningServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-31T04:30:00Z");

    private OrganizationId organizationId;
    private Principal owner;
    private TeamInitialization team;
    private AgentTemplateDefinition template;
    private TeamObserverInitialization disabled;
    private TeamObserverInitialization active;
    private AgentConfigurationVersion configuration;
    private AgentTemplateCatalogInitializer templateCatalog;
    private DefaultTeamObserverService observers;
    private DefaultTeamObserverRepository observerRepository;
    private AgentConfigurationRepository configurations;
    private AgentConfigurationApplicationService configurationService;
    private TeamObserverProvisioningService service;

    @BeforeEach
    void setUp() {
        organizationId = OrganizationId.generate();
        owner = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Owner",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        team = TeamInitialization.create(owner, "Observer repair", NOW);
        template = TeamObserverTemplate.create(organizationId, owner.id(), NOW);
        disabled = TeamObserverInitialization.createDefault(
                team.team(),
                team.defaultWorkspace(),
                team.ownerMember(),
                owner,
                template,
                NOW);
        configuration = configuration(disabled);
        active = disabled.activate(configuration, owner.id(), NOW);

        TeamRepository teams = mock(TeamRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        TeamMemberRepository members = mock(TeamMemberRepository.class);
        PrincipalRepository principals = mock(PrincipalRepository.class);
        templateCatalog = mock(AgentTemplateCatalogInitializer.class);
        AgentTemplateRepository templates = mock(AgentTemplateRepository.class);
        observers = mock(DefaultTeamObserverService.class);
        observerRepository = mock(DefaultTeamObserverRepository.class);
        configurations = mock(AgentConfigurationRepository.class);
        configurationService = mock(AgentConfigurationApplicationService.class);

        when(teams.findById(organizationId, team.team().id()))
                .thenReturn(Optional.of(team.team()));
        when(workspaces.findById(organizationId, team.defaultWorkspace().id()))
                .thenReturn(Optional.of(team.defaultWorkspace()));
        when(members.findById(organizationId, team.ownerMember().id()))
                .thenReturn(Optional.of(team.ownerMember()));
        when(principals.findById(organizationId, owner.id())).thenReturn(Optional.of(owner));
        when(templates.findByVersion(
                        AgentTemplatePublisherScope.organization(organizationId),
                        TeamObserverTemplate.VERSION))
                .thenReturn(Optional.of(template));
        when(observers.ensureDefault(
                        team.team(),
                        team.defaultWorkspace(),
                        team.ownerMember(),
                        owner,
                        template))
                .thenReturn(disabled);
        when(observers.activateDefault(organizationId, team.team().id(), owner))
                .thenReturn(active);

        service = new TeamObserverProvisioningService(
                teams,
                workspaces,
                members,
                principals,
                templateCatalog,
                templates,
                observers,
                observerRepository,
                configurations,
                configurationService,
                () -> NOW);
    }

    @Test
    void provisionsConfiguresAndActivatesAPreviouslyMissingObserver() {
        SelectableModelOption model = selectableModel();
        when(observerRepository.findByTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.empty());
        when(configurationService.selectable(
                        any(),
                        eq(organizationId),
                        eq(team.team().id()),
                        eq(disabled.agentProfile().id()),
                        eq(AgentExecutionScope.TEAM)))
                .thenReturn(List.of(model));

        TeamObserverInitialization result = service.ensureReady(
                organizationId, team.team().id());

        assertEquals(AgentProfileStatus.ACTIVE, result.agentProfile().status());
        verify(templateCatalog).initialize(organizationId, owner.id(), NOW);
        verify(observers).ensureDefault(
                team.team(),
                team.defaultWorkspace(),
                team.ownerMember(),
                owner,
                template);
        ArgumentCaptor<AgentConfigurationDraft> draft =
                ArgumentCaptor.forClass(AgentConfigurationDraft.class);
        verify(configurationService).append(
                any(), eq(team.team().id()), eq(disabled.agentProfile().id()), eq(0L), draft.capture());
        assertEquals(
                AgentModelBindingKind.DIRECT,
                draft.getValue().teamModelBinding().orElseThrow().kind());
        verify(observers).activateDefault(organizationId, team.team().id(), owner);
    }

    @Test
    void repeatedRepairReturnsTheActiveObserverWithoutReconfiguringIt() {
        SelectableModelOption model = selectableModel();
        when(observerRepository.findByTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.empty());
        when(configurationService.selectable(any(), any(), any(), any(), any()))
                .thenReturn(List.of(model));

        service.ensureReady(organizationId, team.team().id());
        clearInvocations(configurationService, observers, configurations);
        when(observerRepository.findByTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(active));

        TeamObserverInitialization replay = service.ensureReady(
                organizationId, team.team().id());

        assertEquals(AgentProfileStatus.ACTIVE, replay.agentProfile().status());
        verify(configurationService, never()).selectable(any(), any(), any(), any(), any());
        verify(configurationService, never()).append(any(), any(), any(), any(Long.class), any());
        verify(configurations, never()).findCurrent(any(), any());
        verify(observers, never()).activateDefault(any(), any(), any());
    }

    @Test
    void keepsTheObserverDisabledWhenNoSafeTeamModelIsSelectable() {
        when(observerRepository.findByTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.empty());
        when(configurationService.selectable(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThrows(
                DomainValidationException.class,
                () -> service.ensureReady(organizationId, team.team().id()));

        assertEquals(AgentProfileStatus.DISABLED, disabled.agentProfile().status());
        verify(configurationService, never()).append(any(), any(), any(), any(Long.class), any());
        verify(observers, never()).activateDefault(any(), any(), any());
    }

    @Test
    void preservesAnExistingConfigurationAndOnlyFinishesActivation() {
        when(observerRepository.findByTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.of(configuration));

        TeamObserverInitialization result = service.ensureReady(
                organizationId, team.team().id());

        assertEquals(AgentProfileStatus.ACTIVE, result.agentProfile().status());
        verify(configurationService, never()).selectable(any(), any(), any(), any(), any());
        verify(configurationService, never()).append(any(), any(), any(), any(Long.class), any());
        verify(observers).activateDefault(organizationId, team.team().id(), owner);
    }

    private SelectableModelOption selectableModel() {
        SelectableModelOption option = mock(SelectableModelOption.class);
        AgentModelSelection selection = mock(AgentModelSelection.class);
        ModelCatalogCoordinate coordinate = mock(ModelCatalogCoordinate.class);
        when(option.selection()).thenReturn(selection);
        when(selection.connectionId()).thenReturn(ModelConnectionId.generate());
        when(selection.catalogCoordinate()).thenReturn(coordinate);
        when(coordinate.entryId()).thenReturn(ModelCatalogEntryId.generate());
        when(coordinate.catalogRevision()).thenReturn(new ModelCatalogRevision(1));
        return option;
    }

    private AgentConfigurationVersion configuration(TeamObserverInitialization observer) {
        return AgentConfigurationVersion.createInitial(
                observer.agentProfile(),
                template,
                Optional.empty(),
                Optional.empty(),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                new PolicyPackReference(PolicyPackId.generate(), 1),
                SafeModelGenerateOptions.defaults(),
                owner.id(),
                NOW);
    }
}
