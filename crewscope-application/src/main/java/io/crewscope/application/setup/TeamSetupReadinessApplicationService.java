package io.crewscope.application.setup;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.github.GitHubProviderRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.provider.ConnectionRepository;
import io.crewscope.application.runtime.RuntimeFleetHealth;
import io.crewscope.application.runtime.RuntimeObservationService;
import io.crewscope.application.runtime.RuntimeFleetSummary;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.application.workitem.WorkProjectRepository;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentDirectModelBinding;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.coding.RepositoryBinding;
import io.crewscope.domain.coding.RepositoryBindingStatus;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelRegistryStatus;
import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionStatus;
import io.crewscope.domain.provider.ProviderOwner;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberStatus;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.workitem.WorkProject;
import io.crewscope.domain.workitem.WorkProjectStatus;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Builds the member-safe Setup Readiness snapshot from existing authoritative repositories. */
public final class TeamSetupReadinessApplicationService {

    private static final int MAX_PROFILES = 200;
    private static final String GITHUB_CONNECTOR = "github-source-code";
    private static final String LARK_CONNECTOR = "lark-collaboration";

    private final io.crewscope.application.workitem.WorkItemAccessPolicy accessPolicy;
    private final TeamMembershipQuery memberships;
    private final AgentProfileRepository profiles;
    private final AgentConfigurationRepository configurations;
    private final AgentModelDefaultRepository modelDefaults;
    private final ModelConnectionRepository modelConnections;
    private final ModelCatalogEntryRepository catalogEntries;
    private final ModelProviderDefinitionRepository providers;
    private final WorkProjectRepository projects;
    private final RepositoryBindingRepository bindings;
    private final ConnectionRepository connections;
    private final GitHubProviderRepository github;
    private final RuntimeObservationService runtimeObservation;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public TeamSetupReadinessApplicationService(
            io.crewscope.application.workitem.WorkItemAccessPolicy accessPolicy,
            TeamMembershipQuery memberships,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            AgentModelDefaultRepository modelDefaults,
            ModelConnectionRepository modelConnections,
            ModelCatalogEntryRepository catalogEntries,
            ModelProviderDefinitionRepository providers,
            WorkProjectRepository projects,
            RepositoryBindingRepository bindings,
            ConnectionRepository connections,
            GitHubProviderRepository github,
            RuntimeObservationService runtimeObservation,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.modelDefaults = Objects.requireNonNull(modelDefaults, "modelDefaults");
        this.modelConnections = Objects.requireNonNull(modelConnections, "modelConnections");
        this.catalogEntries = Objects.requireNonNull(catalogEntries, "catalogEntries");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.github = Objects.requireNonNull(github, "github");
        this.runtimeObservation = Objects.requireNonNull(runtimeObservation, "runtimeObservation");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns a snapshot without creating or mutating any Readiness-specific persistence fact. */
    public TeamSetupReadinessView get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            RuntimeEnvironment environment) {
        return transactions.required(() -> {
            Team team = accessPolicy.requireVisibleTeam(context, organizationId, teamId);
            UtcTimestamp now = timeProvider.now();
            Principal actor = context.actor();
            TeamMember member = memberships.findByTeam(organizationId, teamId).stream()
                    .filter(value -> value.status() == TeamMemberStatus.ACTIVE)
                    .filter(value -> value.userPrincipalId().equals(actor.id()))
                    .findFirst()
                    .orElseThrow(() -> new PolicyDeniedException("read Team setup readiness"));
            List<io.crewscope.domain.workspace.AgentProfile> visibleProfiles = profiles.findByTeam(
                    organizationId, teamId, 0, MAX_PROFILES);
            List<WorkProject> visibleProjects = projects.findByTeam(organizationId, teamId);
            RuntimeFacts runtime = runtimeFacts(context, organizationId, teamId, environment);

            List<TeamSetupReadinessItem> items = new ArrayList<>();
            items.add(personalConversation(context, organizationId, team, member, visibleProfiles, now));
            items.add(teamTask(context, organizationId, team, visibleProfiles, runtime, now));
            items.add(codingReview(context, organizationId, team, visibleProfiles, visibleProjects, runtime, now));
            items.add(githubDraftPr(context, organizationId, team, now));
            items.add(larkNotifications(context, organizationId, team, now));
            items.add(teamObserver(context, organizationId, team, visibleProfiles, runtime, now));
            String snapshotVersion = snapshotVersion(team, visibleProfiles, visibleProjects, items, runtime, now);
            boolean requiredReady = items.stream()
                    .filter(TeamSetupReadinessItem::required)
                    .allMatch(TeamSetupReadinessItem::ready);
            return new TeamSetupReadinessView(
                    organizationId, teamId, snapshotVersion, now, items, requiredReady);
        });
    }

    private TeamSetupReadinessItem personalConversation(
            TeamAccessContext context,
            OrganizationId organizationId,
            Team team,
            TeamMember member,
            List<io.crewscope.domain.workspace.AgentProfile> profiles,
            UtcTimestamp now) {
        Optional<io.crewscope.domain.workspace.AgentProfile> profile = profiles.stream()
                .filter(value -> value.defaultProfile())
                .filter(value -> value.runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT)
                .filter(value -> value.ownership().ownerMemberId().map(member.id()::equals).orElse(false))
                .filter(value -> value.status().name().equals("ACTIVE"))
                .findFirst();
        if (profile.isPresent() && modelReady(profile.orElseThrow(), team, now)) {
            return ready(TeamSetupCapability.PERSONAL_CONVERSATION, true, "当前成员");
        }
        return actionRequired(
                TeamSetupCapability.PERSONAL_CONVERSATION,
                true,
                "PERSONAL_AGENT_CONFIGURATION_REQUIRED",
                canConfigure(context, organizationId, team.id(), TeamPermission.AGENT_MANAGE),
                "OPEN_AGENT_SETTINGS",
                "当前成员");
    }

    private TeamSetupReadinessItem teamTask(
            TeamAccessContext context,
            OrganizationId organizationId,
            Team team,
            List<io.crewscope.domain.workspace.AgentProfile> profiles,
            RuntimeFacts runtime,
            UtcTimestamp now) {
        boolean agentReady = profiles.stream()
                .filter(value -> value.status().name().equals("ACTIVE"))
                .filter(value -> value.runtimeRole() == AgentRuntimeRole.TEAM_COORDINATOR)
                .anyMatch(value -> modelReady(value, team, now));
        if (agentReady && runtime.available()) {
            return ready(TeamSetupCapability.TEAM_TASK, true, "Team 管理员");
        }
        if (!runtime.available()) {
            return unavailable(
                    TeamSetupCapability.TEAM_TASK,
                    true,
                    "RUNTIME_UNAVAILABLE",
                    "Team 管理员");
        }
        String reason = "TEAM_AGENT_CONFIGURATION_REQUIRED";
        return actionRequired(
                TeamSetupCapability.TEAM_TASK,
                true,
                reason,
                canConfigure(context, organizationId, team.id(), TeamPermission.AGENT_MANAGE),
                "OPEN_AGENT_SETTINGS",
                "Team 管理员");
    }

    private TeamSetupReadinessItem codingReview(
            TeamAccessContext context,
            OrganizationId organizationId,
            Team team,
            List<io.crewscope.domain.workspace.AgentProfile> profiles,
            List<WorkProject> projects,
            RuntimeFacts runtime,
            UtcTimestamp now) {
        Optional<WorkProject> activeProject = projects.stream()
                .filter(value -> value.status() == WorkProjectStatus.ACTIVE)
                .findFirst();
        boolean repositoryReady = activeProject.map(project -> bindings.findByWorkProject(
                        organizationId, team.id(), project.id()).stream()
                        .anyMatch(value -> value.status() == RepositoryBindingStatus.ACTIVE))
                .orElse(false);
        boolean specialistReady = profiles.stream()
                .filter(value -> value.status().name().equals("ACTIVE"))
                .filter(value -> value.runtimeRole() == AgentRuntimeRole.SPECIALIST)
                .anyMatch(value -> modelReady(value, team, now));
        if (activeProject.isPresent() && repositoryReady && specialistReady && runtime.codingAvailable()) {
            return ready(TeamSetupCapability.CODING_REVIEW, true, "Team 管理员");
        }
        String reason = activeProject.isEmpty() ? "WORKPROJECT_REQUIRED"
                : !repositoryReady ? "MANAGED_REPOSITORY_REQUIRED"
                : !specialistReady ? "CODING_AGENT_CONFIGURATION_REQUIRED"
                : "CODING_RUNTIME_UNAVAILABLE";
        if ("CODING_RUNTIME_UNAVAILABLE".equals(reason)) {
            return unavailable(
                    TeamSetupCapability.CODING_REVIEW,
                    true,
                    reason,
                    "Team 管理员");
        }
        TeamPermission permission = activeProject.isEmpty()
                ? TeamPermission.WORK_PROJECT_MANAGE : TeamPermission.AGENT_MANAGE;
        return actionRequired(
                TeamSetupCapability.CODING_REVIEW,
                true,
                reason,
                canConfigure(context, organizationId, team.id(), permission),
                activeProject.isEmpty() ? "OPEN_WORKPROJECT_SETTINGS" : "OPEN_AGENT_SETTINGS",
                "Team 管理员");
    }

    private TeamSetupReadinessItem githubDraftPr(
            TeamAccessContext context, OrganizationId organizationId, Team team, UtcTimestamp now) {
        List<Connection> values = connections.findByOwner(ProviderOwner.team(team)).stream()
                .filter(value -> GITHUB_CONNECTOR.equals(value.connectorKey()))
                .toList();
        Optional<Connection> active = values.stream()
                .filter(value -> value.status() == ConnectionStatus.ACTIVE)
                .filter(value -> value.isUsableAt(now))
                .findFirst();
        if (active.isPresent()) {
            try {
                if (!github.findDeliverableRepositories(organizationId, active.orElseThrow().id()).isEmpty()) {
                    return ready(TeamSetupCapability.GITHUB_DRAFT_PR, false, "Team 管理员");
                }
                return actionRequired(
                        TeamSetupCapability.GITHUB_DRAFT_PR,
                        false,
                        "GITHUB_REPOSITORY_IMPORT_REQUIRED",
                        canConfigure(context, organizationId, team.id(), TeamPermission.PROVIDER_MANAGE),
                        "START_GITHUB_IMPORT",
                        "Team 管理员");
            } catch (RuntimeException unavailable) {
                return unavailable(
                        TeamSetupCapability.GITHUB_DRAFT_PR,
                        false,
                        "GITHUB_CATALOG_UNAVAILABLE",
                        "Team 管理员");
            }
        }
        return actionRequired(
                TeamSetupCapability.GITHUB_DRAFT_PR,
                false,
                "GITHUB_CONNECTION_REQUIRED",
                canConfigure(context, organizationId, team.id(), TeamPermission.PROVIDER_MANAGE),
                "OPEN_GITHUB_SETTINGS",
                "Team 管理员");
    }

    private TeamSetupReadinessItem larkNotifications(
            TeamAccessContext context, OrganizationId organizationId, Team team, UtcTimestamp now) {
        boolean connected = connections.findByOwner(ProviderOwner.team(team)).stream()
                .anyMatch(value -> LARK_CONNECTOR.equals(value.connectorKey())
                        && value.status() == ConnectionStatus.ACTIVE && value.isUsableAt(now));
        if (connected) {
            return ready(TeamSetupCapability.LARK_NOTIFICATIONS, false, "Team 管理员");
        }
        return actionRequired(
                TeamSetupCapability.LARK_NOTIFICATIONS,
                false,
                "LARK_CONNECTION_REQUIRED",
                canConfigure(context, organizationId, team.id(), TeamPermission.PROVIDER_MANAGE),
                "OPEN_LARK_SETTINGS",
                "Team 管理员");
    }

    private TeamSetupReadinessItem teamObserver(
            TeamAccessContext context,
            OrganizationId organizationId,
            Team team,
            List<io.crewscope.domain.workspace.AgentProfile> profiles,
            RuntimeFacts runtime,
            UtcTimestamp now) {
        boolean observer = profiles.stream()
                .filter(value -> value.status().name().equals("ACTIVE"))
                .anyMatch(value -> value.runtimeRole() == AgentRuntimeRole.TEAM_COORDINATOR
                        && value.templateVersion().key().value().equals("team-observer")
                        && modelReady(value, team, now));
        if (observer && runtime.available()) {
            return ready(TeamSetupCapability.TEAM_OBSERVER, false, "Team 成员");
        }
        return actionRequired(
                TeamSetupCapability.TEAM_OBSERVER,
                false,
                "TEAM_OBSERVER_CONFIGURATION_REQUIRED",
                canConfigure(context, organizationId, team.id(), TeamPermission.AGENT_MANAGE),
                "OPEN_AGENT_SETTINGS",
                "Team 管理员");
    }

    private boolean modelReady(
            io.crewscope.domain.workspace.AgentProfile profile, Team team, UtcTimestamp now) {
        Optional<AgentConfigurationVersion> config = configurations.findCurrent(
                profile.scope().organizationId(), profile.id());
        if (config.isEmpty()) {
            return false;
        }
        AgentExecutionModelBinding binding = profile.runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT
                ? config.orElseThrow().personalModelBinding().orElse(null)
                : config.orElseThrow().teamModelBinding().orElse(null);
        if (binding == null || binding.kind() == AgentModelBindingKind.ORCHESTRATION_ONLY) {
            return false;
        }
        AgentDirectModelBinding direct = binding.kind() == AgentModelBindingKind.DIRECT
                ? binding.directBinding().orElseThrow()
                : inheritedDefault(profile, team, binding);
        if (direct == null) {
            return false;
        }
        return selectionReady(direct.primary(), now)
                && direct.fallback().map(value -> selectionReady(value, now)).orElse(true);
    }

    private AgentDirectModelBinding inheritedDefault(
            io.crewscope.domain.workspace.AgentProfile profile,
            Team team,
            AgentExecutionModelBinding binding) {
        AgentModelDefaultScope teamScope = AgentModelDefaultScope.team(
                team.organizationId(), team.id());
        Optional<AgentModelDefault> candidate = modelDefaults.findCurrent(
                teamScope, profile.templateVersion(), binding.executionScope());
        if (candidate.isEmpty()) {
            candidate = modelDefaults.findCurrent(
                    AgentModelDefaultScope.organization(team.organizationId()),
                    profile.templateVersion(), binding.executionScope());
        }
        return candidate.map(AgentModelDefault::modelBinding).orElse(null);
    }

    private boolean selectionReady(
            io.crewscope.domain.agent.AgentModelSelection selection, UtcTimestamp now) {
        if (selection == null) {
            return false;
        }
        Optional<ModelConnection> connection = modelConnections.findById(
                selection.organizationId(), selection.connectionId());
        Optional<ModelProviderDefinition> provider = providers.findByKey(selection.providerKey());
        Optional<ModelCatalogEntry> catalog = catalogEntries.findByCoordinate(selection.catalogCoordinate());
        return connection.filter(value -> value.status() == ModelConnectionStatus.ACTIVE)
                .filter(value -> value.health().status() == ModelConnectionHealthStatus.HEALTHY)
                .filter(value -> value.health().isHealthyFor(value.credentialBinding().credentialVersion()))
                .filter(value -> provider.map(item -> item.status() == ModelRegistryStatus.ACTIVE
                        && item.contentHash().equals(value.providerDefinitionHash())).orElse(false))
                .isPresent()
                && catalog.map(value -> value.status() == ModelRegistryStatus.ACTIVE
                        && value.contentHash().equals(selection.catalogContentHash())).orElse(false);
    }

    private RuntimeFacts runtimeFacts(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            RuntimeEnvironment environment) {
        try {
            RuntimeFleetSummary summary = runtimeObservation.summary(
                    context, organizationId, teamId, environment);
            boolean coding = summary.codingWorkspaces()
                    .map(value -> value.health() == io.crewscope.application.runtime.CodingRuntimeComponentHealth.HEALTHY)
                    .orElse(summary.health() == RuntimeFleetHealth.HEALTHY);
            return new RuntimeFacts(summary.health() != RuntimeFleetHealth.UNAVAILABLE, coding,
                    summary.health().name());
        } catch (RuntimeException failure) {
            return new RuntimeFacts(false, false, "UNAVAILABLE");
        }
    }

    private boolean canConfigure(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamPermission permission) {
        if (context.platformAdministrator()) {
            return true;
        }
        try {
            accessPolicy.requireTeamPermission(
                    context, organizationId, teamId, permission, timeProvider.now(), "configure Team setup");
            return true;
        } catch (PolicyDeniedException denied) {
            return false;
        }
    }

    private static TeamSetupReadinessItem ready(
            TeamSetupCapability capability, boolean required, String responsibleParty) {
        return new TeamSetupReadinessItem(
                capability, required, TeamSetupReadinessStatus.READY, "READY", true,
                responsibleParty, Optional.empty());
    }

    private static TeamSetupReadinessItem actionRequired(
            TeamSetupCapability capability,
            boolean required,
            String reasonCode,
            boolean canConfigure,
            String actionKey,
            String responsibleParty) {
        return new TeamSetupReadinessItem(
                capability,
                required,
                canConfigure
                        ? TeamSetupReadinessStatus.ACTION_REQUIRED
                        : TeamSetupReadinessStatus.BLOCKED,
                reasonCode,
                canConfigure,
                canConfigure ? "当前成员" : responsibleParty,
                canConfigure ? Optional.of(actionKey) : Optional.empty());
    }

    private static TeamSetupReadinessItem unavailable(
            TeamSetupCapability capability,
            boolean required,
            String reasonCode,
            String responsibleParty) {
        return new TeamSetupReadinessItem(
                capability,
                required,
                TeamSetupReadinessStatus.UNAVAILABLE,
                reasonCode,
                false,
                responsibleParty,
                Optional.empty());
    }

    private static String snapshotVersion(
            Team team,
            List<io.crewscope.domain.workspace.AgentProfile> profiles,
            List<WorkProject> projects,
            List<TeamSetupReadinessItem> items,
            RuntimeFacts runtime,
            UtcTimestamp now) {
        return Integer.toUnsignedString(Objects.hash(
                team.version(),
                profiles.stream().mapToLong(io.crewscope.domain.workspace.AgentProfile::version).sum(),
                projects.stream().mapToLong(WorkProject::version).sum(),
                items,
                runtime.health()));
    }

    private record RuntimeFacts(boolean available, boolean codingAvailable, String health) {}
}
