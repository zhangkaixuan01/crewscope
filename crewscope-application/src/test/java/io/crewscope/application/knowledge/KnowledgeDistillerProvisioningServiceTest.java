package io.crewscope.application.knowledge;

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
import io.crewscope.application.transaction.TransactionExecutor;
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
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
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
import io.crewscope.domain.workspace.AgentProfileStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** First-use lazy provisioning for the built-in Knowledge Distiller (D10). */
class KnowledgeDistillerProvisioningServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-02T04:30:00Z");

    private OrganizationId organizationId;
    private Principal owner;
    private TeamInitialization team;
    private AgentTemplateDefinition template;
    private KnowledgeDistillerInitialization disabled;
    private KnowledgeDistillerInitialization active;
    private AgentConfigurationVersion configuration;
    private AgentTemplateCatalogInitializer templateCatalog;
    private KnowledgeDistillerRepository distillers;
    private AgentConfigurationRepository configurations;
    private AgentConfigurationApplicationService configurationService;
    private KnowledgeDistillerProvisioningService service;

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
        team = TeamInitialization.create(owner, "Distiller repair", NOW);
        template = KnowledgeDistillerTemplate.create(organizationId, owner.id(), NOW);
        disabled = KnowledgeDistillerInitialization.createDefault(
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
        distillers = mock(KnowledgeDistillerRepository.class);
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
                        KnowledgeDistillerTemplate.VERSION))
                .thenReturn(Optional.of(template));
        when(distillers.initializeIfAbsent(any())).thenReturn(disabled);
        when(distillers.updateLifecycle(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new KnowledgeDistillerProvisioningService(
                teams,
                workspaces,
                members,
                principals,
                templateCatalog,
                templates,
                distillers,
                configurations,
                configurationService,
                new DirectTransactionExecutor(),
                () -> NOW);
    }

    @Test
    void provisionsConfiguresAndActivatesAPreviouslyMissingDistiller() {
        SelectableModelOption model = selectableModel();
        when(distillers.findDistillerForTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        // Empty during ensureConfiguration, present once the append has "committed".
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(configuration));
        when(configurationService.selectable(
                        any(),
                        eq(organizationId),
                        eq(team.team().id()),
                        eq(disabled.agentProfile().id()),
                        eq(AgentExecutionScope.TEAM)))
                .thenReturn(List.of(model));

        KnowledgeDistillerInitialization result = service.ensureReady(
                organizationId, team.team().id());

        assertEquals(AgentProfileStatus.ACTIVE, result.agentProfile().status());
        verify(templateCatalog).initialize(organizationId, owner.id(), NOW);
        verify(distillers).initializeIfAbsent(any());
        ArgumentCaptor<AgentConfigurationDraft> draft =
                ArgumentCaptor.forClass(AgentConfigurationDraft.class);
        verify(configurationService).append(
                any(), eq(team.team().id()), eq(disabled.agentProfile().id()), eq(0L),
                draft.capture());
        assertEquals(
                AgentModelBindingKind.DIRECT,
                draft.getValue().teamModelBinding().orElseThrow().kind());
        ArgumentCaptor<KnowledgeDistillerInitialization> activated =
                ArgumentCaptor.forClass(KnowledgeDistillerInitialization.class);
        verify(distillers).updateLifecycle(activated.capture());
        assertEquals(AgentProfileStatus.ACTIVE, activated.getValue().agentProfile().status());
        assertEquals(
                disabled.agentProfile().id(), activated.getValue().agentProfile().id());
    }

    @Test
    void repeatedEnsureReadyReturnsTheActiveDistillerWithoutReconfiguringIt() {
        SelectableModelOption model = selectableModel();
        when(distillers.initializeIfAbsent(any())).thenReturn(disabled, active);
        when(distillers.findDistillerForTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(configuration));
        when(configurationService.selectable(any(), any(), any(), any(), any()))
                .thenReturn(List.of(model));

        service.ensureReady(organizationId, team.team().id());
        clearInvocations(configurationService, distillers, configurations);
        when(distillers.findDistillerForTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(active));

        KnowledgeDistillerInitialization replay = service.ensureReady(
                organizationId, team.team().id());

        assertEquals(AgentProfileStatus.ACTIVE, replay.agentProfile().status());
        verify(configurationService, never()).selectable(any(), any(), any(), any(), any());
        verify(configurationService, never()).append(any(), any(), any(), any(Long.class), any());
        verify(configurations, never()).findCurrent(any(), any());
        verify(distillers, never()).updateLifecycle(any());
    }

    @Test
    void failsClosedWhenNoSafeTeamModelIsSelectable() {
        when(distillers.findDistillerForTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.empty());
        when(configurationService.selectable(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> service.ensureReady(organizationId, team.team().id()));

        assertEquals("knowledgeDistiller.modelBinding", failure.error().details().get("field"));
        assertEquals(AgentProfileStatus.DISABLED, disabled.agentProfile().status());
        verify(configurationService, never()).append(any(), any(), any(), any(Long.class), any());
        verify(distillers, never()).updateLifecycle(any());
    }

    @Test
    void preservesAnExistingConfigurationAndOnlyFinishesActivation() {
        when(distillers.findDistillerForTeam(organizationId, team.team().id()))
                .thenReturn(Optional.of(disabled));
        when(configurations.findCurrent(organizationId, disabled.agentProfile().id()))
                .thenReturn(Optional.of(configuration));

        KnowledgeDistillerInitialization result = service.ensureReady(
                organizationId, team.team().id());

        assertEquals(AgentProfileStatus.ACTIVE, result.agentProfile().status());
        verify(configurationService, never()).selectable(any(), any(), any(), any(), any());
        verify(configurationService, never()).append(any(), any(), any(), any(Long.class), any());
        verify(distillers).updateLifecycle(any());
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

    private AgentConfigurationVersion configuration(KnowledgeDistillerInitialization distiller) {
        return AgentConfigurationVersion.createInitial(
                distiller.agentProfile(),
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

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
