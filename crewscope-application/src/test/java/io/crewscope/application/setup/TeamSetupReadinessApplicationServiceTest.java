package io.crewscope.application.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.application.coding.BuildProfileCatalog;
import io.crewscope.application.coding.ProjectExecutionDefaultsRepository;
import io.crewscope.application.coding.RepositoryBindingAccessPolicy;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.runtime.CodingRuntimeComponentHealth;
import io.crewscope.application.runtime.CodingWorkspaceFleetSummary;
import io.crewscope.application.runtime.RuntimeFleetHealth;
import io.crewscope.application.runtime.RuntimeFleetSummary;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkItemAccessPolicy;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentDirectModelBinding;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.coding.BuildProfile;
import io.crewscope.domain.coding.BuildProfileReference;
import io.crewscope.domain.coding.ProjectExecutionDefaults;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.model.ModelCatalogCoordinate;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelCatalogRevision;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealth;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegistryHash;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskFactHash;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectId;
import io.crewscope.domain.workitem.WorkProjectStatus;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.WorkspaceScope;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TeamSetupReadinessApplicationServiceTest {

    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final TeamId TEAM = TeamId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-01T12:00:00Z");
    private static final WorkProjectId PROJECT = WorkProjectId.generate();
    private static final WorkspaceId WORKSPACE = WorkspaceId.generate();
    private static final io.crewscope.domain.coding.RepositoryBindingId BINDING_ID =
            new io.crewscope.domain.coding.RepositoryBindingId(UUID.randomUUID());
    private static final ModelConnectionId CONNECTION_ID =
            new ModelConnectionId(UUID.randomUUID());
    private static final ModelProviderKey PROVIDER_KEY = new ModelProviderKey("deepseek");
    private static final ModelRegistryHash PROVIDER_HASH = new ModelRegistryHash("1".repeat(64));
    private static final ModelRegistryHash CATALOG_HASH = new ModelRegistryHash("2".repeat(64));
    private static final ModelCatalogCoordinate CATALOG_COORDINATE = new ModelCatalogCoordinate(
            new ModelCatalogEntryId(UUID.randomUUID()), PROVIDER_KEY, new ModelId("deepseek-chat"),
            new ModelCatalogRevision(3));
    private static final BuildProfileReference BUILD_PROFILE = new BuildProfileReference(
            "maven-java-17", 1, new TaskFactHash("3".repeat(64)));

    private TeamSetupReadinessApplicationService service;
    private TeamAccessContext access;
    private WorkItemAccessPolicy accessPolicy;
    private Team team;
    private TeamMember member;
    private AgentProfileRepository profiles;
    private AgentConfigurationRepository configurations;
    private ModelConnectionRepository modelConnections;
    private ModelCatalogEntryRepository catalogEntries;
    private ModelProviderDefinitionRepository providers;
    private WorkProjectRepository projects;
    private RepositoryBindingRepository bindings;
    private RepositoryBindingAccessPolicy repositoryAdministration;
    private ProjectExecutionDefaultsRepository executionDefaults;
    private BuildProfileCatalog buildProfiles;
    private ConnectionRepository connections;
    private RuntimeObservationService runtimeObservation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accessPolicy = mock(WorkItemAccessPolicy.class);
        TeamMembershipQuery memberships = mock(TeamMembershipQuery.class);
        profiles = mock(AgentProfileRepository.class);
        configurations = mock(AgentConfigurationRepository.class);
        AgentModelDefaultRepository defaults = mock(AgentModelDefaultRepository.class);
        modelConnections = mock(ModelConnectionRepository.class);
        catalogEntries = mock(ModelCatalogEntryRepository.class);
        providers = mock(ModelProviderDefinitionRepository.class);
        projects = mock(WorkProjectRepository.class);
        bindings = mock(RepositoryBindingRepository.class);
        repositoryAdministration = mock(RepositoryBindingAccessPolicy.class);
        // The write path admits only built-in Team administrators; the default stub models that
        // verdict as "yes" so the existing gap cases keep asserting the administrator's view.
        when(repositoryAdministration.canAdministrate(any(), any(), any(), any())).thenReturn(true);
        executionDefaults = mock(ProjectExecutionDefaultsRepository.class);
        buildProfiles = mock(BuildProfileCatalog.class);
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
                catalogEntries,
                providers,
                projects,
                bindings,
                repositoryAdministration,
                executionDefaults,
                buildProfiles,
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
        doThrow(new PolicyDeniedException("configure Team setup"))
                .when(accessPolicy).requireTeamPermission(
                        any(), any(), any(), any(), any(), any());

        TeamSetupReadinessView result = service.get(
                access, ORGANIZATION, TEAM, new RuntimeEnvironment("development"));

        assertEquals(TeamSetupReadinessStatus.BLOCKED, result.capabilities().get(0).status());
        assertFalse(result.capabilities().get(0).canConfigure());
        assertTrue(result.capabilities().get(0).actionKey().isEmpty());
    }

    @Test
    void reportsExecutionDefaultsRequiredWhenProjectHasNoDefaults() {
        givenCodingReviewReadyExceptDefaults();

        when(executionDefaults.find(ORGANIZATION, TEAM, PROJECT)).thenReturn(Optional.empty());
        TeamSetupReadinessItem absent = codingReviewItem();
        assertEquals(TeamSetupReadinessStatus.ACTION_REQUIRED, absent.status());
        assertEquals("EXECUTION_DEFAULTS_REQUIRED", absent.reasonCode());
        assertEquals(Optional.of("OPEN_EXECUTION_DEFAULTS"), absent.actionKey());
        assertTrue(absent.canConfigure());

        when(executionDefaults.find(ORGANIZATION, TEAM, PROJECT))
                .thenReturn(Optional.of(ProjectExecutionDefaults.empty(
                        ORGANIZATION, TEAM, WORKSPACE, PROJECT)));
        assertEquals("EXECUTION_DEFAULTS_REQUIRED", codingReviewItem().reasonCode());
    }

    @Test
    void reportsExecutionDefaultsRequiredWhenRepositoryOrBuildProfileMissing() {
        givenCodingReviewReadyExceptDefaults();
        givenStoredDefaults(1, Optional.empty(), Optional.empty());

        TeamSetupReadinessItem item = codingReviewItem();

        assertEquals("EXECUTION_DEFAULTS_REQUIRED", item.reasonCode());
        assertEquals(Optional.of("OPEN_EXECUTION_DEFAULTS"), item.actionKey());
    }

    @Test
    void reportsExecutionDefaultsRequiredWhenBindingVersionDrifts() {
        givenCodingReviewReadyExceptDefaults();
        givenStoredDefaults(5, Optional.of(BINDING_ID), Optional.of(BUILD_PROFILE));

        assertEquals("EXECUTION_DEFAULTS_REQUIRED", codingReviewItem().reasonCode());
    }

    @Test
    void reportsExecutionDefaultsRequiredWhenCatalogRetiresBuildProfile() {
        givenCodingReviewReadyExceptDefaults();
        givenStoredDefaults(1, Optional.of(BINDING_ID), Optional.of(BUILD_PROFILE));
        when(buildProfiles.findExact(BUILD_PROFILE)).thenReturn(Optional.empty());

        assertEquals("EXECUTION_DEFAULTS_REQUIRED", codingReviewItem().reasonCode());
    }

    @Test
    void reportsCodingReviewReadyWhenDefaultsComplete() {
        givenCodingReviewReadyExceptDefaults();
        givenStoredDefaults(1, Optional.of(BINDING_ID), Optional.of(BUILD_PROFILE));

        TeamSetupReadinessItem item = codingReviewItem();

        assertEquals(TeamSetupReadinessStatus.READY, item.status());
        assertEquals("READY", item.reasonCode());
        assertTrue(item.actionKey().isEmpty());
    }

    @Test
    void keepsRepositoryGapReasonWhenNoBindingIsActive() {
        givenCodingReviewReadyExceptDefaults();
        givenStoredDefaults(1, Optional.of(BINDING_ID), Optional.of(BUILD_PROFILE));
        RepositoryBinding disabled = activeBinding();
        when(disabled.status()).thenReturn(RepositoryBindingStatus.DISABLED);
        when(bindings.findByWorkProject(ORGANIZATION, TEAM, PROJECT))
                .thenReturn(List.of(disabled));

        assertEquals("MANAGED_REPOSITORY_REQUIRED", codingReviewItem().reasonCode());
    }

    @Test
    void doesNotInspectDefaultsWithoutActiveProject() {
        givenCodingReviewReadyExceptDefaults();
        WorkProject archived = mock(WorkProject.class);
        when(archived.id()).thenReturn(PROJECT);
        when(archived.status()).thenReturn(WorkProjectStatus.ARCHIVED);
        when(projects.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of(archived));

        TeamSetupReadinessItem item = codingReviewItem();

        assertEquals("WORKPROJECT_REQUIRED", item.reasonCode());
        assertEquals(Optional.of("OPEN_WORKPROJECT_SETTINGS"), item.actionKey());
        verify(executionDefaults, never()).find(any(), any(), any());
    }

    @Test
    void hidesExecutionDefaultsActionFromMembersOutsideTheWritePathAdministrators() {
        givenCodingReviewReadyExceptDefaults();
        when(executionDefaults.find(ORGANIZATION, TEAM, PROJECT)).thenReturn(Optional.empty());
        // TEAM_LEAD holds WORK_PROJECT_MANAGE, yet the defaults write path — and the settings
        // route its actionKey opens — admits only built-in Team administrators; readiness must
        // not advertise an action the member cannot complete.
        when(repositoryAdministration.canAdministrate(any(), any(), any(), any())).thenReturn(false);

        TeamSetupReadinessItem item = codingReviewItem();

        assertEquals(TeamSetupReadinessStatus.BLOCKED, item.status());
        assertEquals("EXECUTION_DEFAULTS_REQUIRED", item.reasonCode());
        assertFalse(item.canConfigure());
        assertTrue(item.actionKey().isEmpty());
    }

    /** Everything CODING_REVIEW needs except the A04 execution defaults: project, ACTIVE
     *  binding, SPECIALIST profile with a healthy direct model, healthy coding runtime. */
    private void givenCodingReviewReadyExceptDefaults() {
        RuntimeFleetSummary fleet = mock(RuntimeFleetSummary.class);
        when(fleet.health()).thenReturn(RuntimeFleetHealth.HEALTHY);
        CodingWorkspaceFleetSummary coding = mock(CodingWorkspaceFleetSummary.class);
        when(coding.health()).thenReturn(CodingRuntimeComponentHealth.HEALTHY);
        when(fleet.codingWorkspaces()).thenReturn(Optional.of(coding));
        // doReturn form: the setUp stub already throws on summary(...), and the when() probe
        // call would trip that stub before the new one can be registered.
        org.mockito.Mockito.doReturn(fleet).when(runtimeObservation)
                .summary(any(), any(), any(), any());

        WorkProject project = mock(WorkProject.class);
        when(project.id()).thenReturn(PROJECT);
        when(project.status()).thenReturn(WorkProjectStatus.ACTIVE);
        when(projects.findByTeam(ORGANIZATION, TEAM)).thenReturn(List.of(project));
        // Build the binding before entering the when() stubbing state: stubbing another mock
        // while a when() is waiting for its thenReturn leaves the outer stubbing unfinished.
        RepositoryBinding binding = activeBinding();
        when(bindings.findByWorkProject(ORGANIZATION, TEAM, PROJECT))
                .thenReturn(List.of(binding));

        AgentProfile specialist = mock(AgentProfile.class);
        when(specialist.id()).thenReturn(io.crewscope.domain.workspace.AgentProfileId.generate());
        when(specialist.status()).thenReturn(AgentProfileStatus.ACTIVE);
        when(specialist.runtimeRole()).thenReturn(AgentRuntimeRole.SPECIALIST);
        WorkspaceScope scope = mock(WorkspaceScope.class);
        when(scope.organizationId()).thenReturn(ORGANIZATION);
        when(specialist.scope()).thenReturn(scope);
        when(profiles.findByTeam(ORGANIZATION, TEAM, 0, 200)).thenReturn(List.of(specialist));

        AgentModelSelection selection = mock(AgentModelSelection.class);
        when(selection.organizationId()).thenReturn(ORGANIZATION);
        when(selection.connectionId()).thenReturn(CONNECTION_ID);
        when(selection.providerKey()).thenReturn(PROVIDER_KEY);
        when(selection.catalogCoordinate()).thenReturn(CATALOG_COORDINATE);
        when(selection.catalogContentHash()).thenReturn(CATALOG_HASH);

        AgentDirectModelBinding direct = mock(AgentDirectModelBinding.class);
        when(direct.primary()).thenReturn(selection);
        when(direct.fallback()).thenReturn(Optional.empty());
        AgentExecutionModelBinding execution = mock(AgentExecutionModelBinding.class);
        when(execution.kind()).thenReturn(AgentModelBindingKind.DIRECT);
        when(execution.directBinding()).thenReturn(Optional.of(direct));
        AgentConfigurationVersion configuration = mock(AgentConfigurationVersion.class);
        when(configuration.teamModelBinding()).thenReturn(Optional.of(execution));
        when(configurations.findCurrent(any(), any())).thenReturn(Optional.of(configuration));

        ModelConnectionHealth health = mock(ModelConnectionHealth.class);
        when(health.status()).thenReturn(ModelConnectionHealthStatus.HEALTHY);
        when(health.isHealthyFor(any())).thenReturn(true);
        ModelCredentialBinding credential = mock(ModelCredentialBinding.class);
        when(credential.credentialVersion()).thenReturn(new ModelCredentialVersion(1));
        ModelConnection connection = mock(ModelConnection.class);
        when(connection.status()).thenReturn(ModelConnectionStatus.ACTIVE);
        when(connection.health()).thenReturn(health);
        when(connection.credentialBinding()).thenReturn(credential);
        when(connection.providerDefinitionHash()).thenReturn(PROVIDER_HASH);
        when(modelConnections.findById(ORGANIZATION, CONNECTION_ID))
                .thenReturn(Optional.of(connection));

        ModelProviderDefinition definition = mock(ModelProviderDefinition.class);
        when(definition.status()).thenReturn(ModelRegistryStatus.ACTIVE);
        when(definition.contentHash()).thenReturn(PROVIDER_HASH);
        when(providers.findByKey(PROVIDER_KEY)).thenReturn(Optional.of(definition));

        ModelCatalogEntry entry = mock(ModelCatalogEntry.class);
        when(entry.status()).thenReturn(ModelRegistryStatus.ACTIVE);
        when(entry.contentHash()).thenReturn(CATALOG_HASH);
        when(catalogEntries.findByCoordinate(CATALOG_COORDINATE)).thenReturn(Optional.of(entry));

        BuildProfile profile = mock(BuildProfile.class);
        when(buildProfiles.findExact(BUILD_PROFILE)).thenReturn(Optional.of(profile));
    }

    private RepositoryBinding activeBinding() {
        RepositoryBinding binding = mock(RepositoryBinding.class);
        when(binding.id()).thenReturn(BINDING_ID);
        when(binding.status()).thenReturn(RepositoryBindingStatus.ACTIVE);
        when(binding.version()).thenReturn(1L);
        return binding;
    }

    private void givenStoredDefaults(
            long bindingVersion,
            Optional<io.crewscope.domain.coding.RepositoryBindingId> bindingId,
            Optional<BuildProfileReference> buildProfile) {
        when(executionDefaults.find(ORGANIZATION, TEAM, PROJECT)).thenReturn(Optional.of(
                new ProjectExecutionDefaults(
                        ORGANIZATION, TEAM, WORKSPACE, PROJECT, 1,
                        bindingId,
                        bindingId.map(value -> bindingVersion),
                        Optional.empty(),
                        buildProfile,
                        Optional.empty(),
                        Optional.empty())));
    }

    private TeamSetupReadinessItem codingReviewItem() {
        TeamSetupReadinessView view = service.get(
                access, ORGANIZATION, TEAM, new RuntimeEnvironment("development"));
        return view.capabilities().stream()
                .filter(item -> item.capability() == TeamSetupCapability.CODING_REVIEW)
                .findFirst()
                .orElseThrow();
    }
}
