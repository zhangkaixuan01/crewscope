package io.crewscope.application.knowledge;

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
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lazily provisions one Team's built-in Knowledge Distiller and activates it when a safe
 * TEAM model is selectable (D10). Mirrors the TeamObserver provisioning pattern with every
 * stage independently idempotent, minus the administration surface the Distiller does not
 * need: identity is ensured on the first distillation, never at startup.
 */
public final class KnowledgeDistillerProvisioningService {

    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final TeamMemberRepository members;
    private final PrincipalRepository principals;
    private final AgentTemplateCatalogInitializer templateCatalog;
    private final AgentTemplateRepository templates;
    private final KnowledgeDistillerRepository distillers;
    private final AgentConfigurationRepository configurations;
    private final AgentConfigurationApplicationService configurationService;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public KnowledgeDistillerProvisioningService(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            KnowledgeDistillerRepository distillers,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.members = Objects.requireNonNull(members, "members");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.templateCatalog = Objects.requireNonNull(templateCatalog, "templateCatalog");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.distillers = Objects.requireNonNull(distillers, "distillers");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.configurationService =
                Objects.requireNonNull(configurationService, "configurationService");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Ensures identity, initial model configuration and ACTIVE lifecycle for one Team. */
    public KnowledgeDistillerInitialization ensureReady(
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

        KnowledgeDistillerInitialization current = ensureIdentity(team, workspace, ownerMember, owner);
        if (current.agentProfile().status() == AgentProfileStatus.ACTIVE) {
            return current;
        }
        ensureConfiguration(team, owner, current);
        return activate(team, owner);
    }

    private KnowledgeDistillerInitialization ensureIdentity(
            Team team, Workspace workspace, TeamMember ownerMember, Principal owner) {
        templateCatalog.initialize(team.organizationId(), owner.id(), timeProvider.now());
        AgentTemplateDefinition template = templates
                .findByVersion(
                        AgentTemplatePublisherScope.organization(team.organizationId()),
                        KnowledgeDistillerTemplate.VERSION)
                .map(KnowledgeDistillerTemplate::requireDefinition)
                .orElseThrow(() -> new DomainValidationException(
                        "knowledgeDistiller.template",
                        "the built-in knowledge-distiller@1 template is missing"));
        KnowledgeDistillerInitialization candidate = KnowledgeDistillerInitialization.createDefault(
                team, workspace, ownerMember, owner, template, timeProvider.now());
        return transactions.required(() -> distillers
                .initializeIfAbsent(candidate)
                .requireDefaultFor(team, workspace));
    }

    private void ensureConfiguration(
            Team team, Principal owner, KnowledgeDistillerInitialization distiller) {
        if (configurations
                .findCurrent(team.organizationId(), distiller.agentProfile().id())
                .isPresent()) {
            return;
        }
        TeamAccessContext access = new TeamAccessContext(owner, true);
        var selectable = configurationService.selectable(
                access,
                team.organizationId(),
                team.id(),
                distiller.agentProfile().id(),
                AgentExecutionScope.TEAM);
        if (selectable.isEmpty()) {
            throw new DomainValidationException(
                    "knowledgeDistiller.modelBinding",
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
        String commandCoordinate = "system/knowledge-distiller/"
                + distiller.agentProfile().id()
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
                    context, team.id(), distiller.agentProfile().id(), 0, draft);
        } catch (OptimisticLockConflictException concurrent) {
            if (configurations
                    .findCurrent(team.organizationId(), distiller.agentProfile().id())
                    .isEmpty()) {
                throw concurrent;
            }
        }
    }

    private KnowledgeDistillerInitialization activate(Team team, Principal owner) {
        KnowledgeDistillerInitialization current = distillers
                .findDistillerForTeam(team.organizationId(), team.id())
                .orElseThrow(() -> new DomainValidationException(
                        "knowledgeDistiller", "the built-in Distiller was not initialized"));
        if (current.agentProfile().status() == AgentProfileStatus.ACTIVE) {
            return current;
        }
        try {
            return transactions.required(() -> {
                KnowledgeDistillerInitialization latest = distillers
                        .findDistillerForTeam(team.organizationId(), team.id())
                        .orElseThrow();
                if (latest.agentProfile().status() == AgentProfileStatus.ACTIVE) {
                    return latest;
                }
                var configuration = configurations
                        .findCurrent(team.organizationId(), latest.agentProfile().id())
                        .orElseThrow(() -> new DomainValidationException(
                                "knowledgeDistiller.configuration",
                                "a current TEAM model configuration is required"
                                        + " before activation"));
                latest.requireActivationConfiguration(configuration);
                return distillers.updateLifecycle(latest.activate(
                        configuration, owner.id(), timeProvider.now()));
            });
        } catch (OptimisticLockConflictException concurrent) {
            KnowledgeDistillerInitialization committed = distillers
                    .findDistillerForTeam(team.organizationId(), team.id())
                    .orElseThrow();
            if (committed.agentProfile().status() == AgentProfileStatus.ACTIVE) {
                return committed;
            }
            throw concurrent;
        }
    }
}
