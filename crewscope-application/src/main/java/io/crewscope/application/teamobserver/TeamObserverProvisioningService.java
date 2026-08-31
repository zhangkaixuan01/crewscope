package io.crewscope.application.teamobserver;

import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationDraft;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentModelBindingDraft;
import io.crewscope.application.agent.AgentModelSelectionDraft;
import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.teamobserver.TeamObserverInitialization;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repairs one Team's built-in Observer and activates it when a safe TEAM model is selectable.
 *
 * <p>Every stage is independently idempotent. This lets Team creation persist a disabled identity
 * without depending on an external model, while startup and first invocation can finish the model
 * configuration after connections become healthy.
 */
public final class TeamObserverProvisioningService
        implements TeamObserverInitializer, TeamObserverReadiness {

    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final TeamMemberRepository members;
    private final PrincipalRepository principals;
    private final AgentTemplateCatalogInitializer templateCatalog;
    private final AgentTemplateRepository templates;
    private final DefaultTeamObserverService observers;
    private final DefaultTeamObserverRepository observerRepository;
    private final AgentConfigurationRepository configurations;
    private final AgentConfigurationApplicationService configurationService;
    private final TimeProvider timeProvider;

    public TeamObserverProvisioningService(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            DefaultTeamObserverService observers,
            DefaultTeamObserverRepository observerRepository,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TimeProvider timeProvider) {
        this.teams = java.util.Objects.requireNonNull(teams, "teams");
        this.workspaces = java.util.Objects.requireNonNull(workspaces, "workspaces");
        this.members = java.util.Objects.requireNonNull(members, "members");
        this.principals = java.util.Objects.requireNonNull(principals, "principals");
        this.templateCatalog = java.util.Objects.requireNonNull(templateCatalog, "templateCatalog");
        this.templates = java.util.Objects.requireNonNull(templates, "templates");
        this.observers = java.util.Objects.requireNonNull(observers, "observers");
        this.observerRepository =
                java.util.Objects.requireNonNull(observerRepository, "observerRepository");
        this.configurations =
                java.util.Objects.requireNonNull(configurations, "configurations");
        this.configurationService =
                java.util.Objects.requireNonNull(configurationService, "configurationService");
        this.timeProvider = java.util.Objects.requireNonNull(timeProvider, "timeProvider");
    }

    @Override
    public void initialize(
            Team team, Workspace workspace, TeamMember ownerMember, Principal ownerUser) {
        Team requiredTeam = java.util.Objects.requireNonNull(team, "team");
        Principal requiredOwner = java.util.Objects.requireNonNull(ownerUser, "ownerUser");
        templateCatalog.initialize(
                requiredTeam.organizationId(), requiredOwner.id(), timeProvider.now());
        var template = templates
                .findByVersion(
                        io.crewscope.domain.agent.AgentTemplatePublisherScope.organization(
                                requiredTeam.organizationId()),
                        TeamObserverTemplate.VERSION)
                .map(TeamObserverTemplate::requireDefinition)
                .orElseThrow(() -> new DomainValidationException(
                        "teamObserver.template", "the built-in team-observer@1 template is missing"));
        observers.ensureDefault(requiredTeam, workspace, ownerMember, requiredOwner, template);
    }

    /** Ensures identity, initial model configuration and ACTIVE lifecycle for one Team. */
    @Override
    public TeamObserverInitialization ensureReady(
            OrganizationId organizationId, TeamId teamId) {
        Team team = teams.findById(organizationId, teamId)
                .filter(Team::isActive)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        Workspace workspace = workspaces
                .findById(organizationId, team.defaultWorkspaceId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Workspace", team.defaultWorkspaceId()));
        TeamMember ownerMember = members
                .findById(organizationId, team.ownerMemberId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "TeamMember", team.ownerMemberId()));
        Principal owner = principals
                .findById(organizationId, ownerMember.userPrincipalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", ownerMember.userPrincipalId()));

        initialize(team, workspace, ownerMember, owner);
        TeamObserverInitialization observer = observerRepository
                .findByTeam(organizationId, teamId)
                .orElseThrow(() -> new DomainValidationException(
                        "teamObserver", "the built-in Observer was not initialized"));
        if (observer.agentProfile().status() == AgentProfileStatus.ACTIVE) {
            return observer;
        }
        ensureConfiguration(team, owner, observer);
        return activate(team, owner);
    }

    private void ensureConfiguration(
            Team team, Principal owner, TeamObserverInitialization observer) {
        if (configurations
                .findCurrent(team.organizationId(), observer.agentProfile().id())
                .isPresent()) {
            return;
        }
        TeamAccessContext access = new TeamAccessContext(owner, true);
        var selectable = configurationService.selectable(
                access,
                team.organizationId(),
                team.id(),
                observer.agentProfile().id(),
                AgentExecutionScope.TEAM);
        if (selectable.isEmpty()) {
            throw new DomainValidationException(
                    "teamObserver.modelBinding",
                    "requires one healthy policy-approved TEAM or ORGANIZATION model");
        }
        var selection = selectable.get(0).selection();
        var coordinate = selection.catalogCoordinate();
        AgentConfigurationDraft draft = new AgentConfigurationDraft(
                Optional.empty(),
                Optional.of(AgentModelBindingDraft.direct(
                        new AgentModelSelectionDraft(
                                selection.connectionId(),
                                coordinate.entryId(),
                                coordinate.catalogRevision()),
                        Optional.empty())),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                SafeModelGenerateOptions.defaults());
        String commandCoordinate = "system/team-observer/"
                + observer.agentProfile().id()
                + "/configuration-v1";
        UUID correlationId = UUID.nameUUIDFromBytes(
                commandCoordinate.getBytes(StandardCharsets.UTF_8));
        TeamCommandContext context = new TeamCommandContext(
                access,
                IdempotencyKey.from(commandCoordinate),
                correlationId,
                Optional.empty());
        try {
            configurationService.append(
                    context, team.id(), observer.agentProfile().id(), 0, draft);
        } catch (OptimisticLockConflictException concurrent) {
            if (configurations
                    .findCurrent(team.organizationId(), observer.agentProfile().id())
                    .isEmpty()) {
                throw concurrent;
            }
        }
    }

    private TeamObserverInitialization activate(Team team, Principal owner) {
        TeamObserverInitialization current = observerRepository
                .findByTeam(team.organizationId(), team.id())
                .orElseThrow();
        if (current.agentProfile().status() == AgentProfileStatus.ACTIVE) {
            return current;
        }
        try {
            return observers.activateDefault(team.organizationId(), team.id(), owner);
        } catch (OptimisticLockConflictException concurrent) {
            TeamObserverInitialization committed = observerRepository
                    .findByTeam(team.organizationId(), team.id())
                    .orElseThrow();
            if (committed.agentProfile().status() == AgentProfileStatus.ACTIVE) {
                return committed;
            }
            throw concurrent;
        }
    }
}
