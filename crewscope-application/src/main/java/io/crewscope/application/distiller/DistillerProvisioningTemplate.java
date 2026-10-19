package io.crewscope.application.distiller;

import io.crewscope.application.agent.AgentConfigurationApplicationService;
import io.crewscope.application.agent.AgentConfigurationDraft;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentModelBindingDraft;
import io.crewscope.application.agent.AgentModelSelectionDraft;
import io.crewscope.application.agent.AgentTemplateCatalogInitializer;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.knowledge.KnowledgeDistillerRepository;
import io.crewscope.application.skill.SkillDistillerRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMemberRepository;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentTemplateVersion;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerInitialization;
import io.crewscope.domain.knowledge.distiller.KnowledgeDistillerTemplate;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.distiller.SkillDistillerInitialization;
import io.crewscope.domain.skill.distiller.SkillDistillerTemplate;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lazy provisioning sequence shared by the Knowledge and Skill Distiller services
 * (M13-C03): every stage is independently idempotent and identity is ensured on the
 * first distillation, never at startup. The per-kind differences are the template
 * contract, the deterministic pair type, the error prefix and the system command
 * coordinate prefix.
 */
public final class DistillerProvisioningTemplate<I> {

    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final TeamMemberRepository members;
    private final PrincipalRepository principals;
    private final AgentTemplateCatalogInitializer templateCatalog;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final AgentConfigurationApplicationService configurationService;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final AgentTemplateVersion version;
    private final String errorPrefix;
    private final String templateLabel;
    private final String commandPrefix;
    private final ProfileOf<I> profileOf;
    private final DefinitionCheck definitionCheck;
    private final CreateDefault<I> createDefault;
    private final RequireDefaultFor<I> requireDefaultFor;
    private final RequireActivationConfiguration<I> requireActivationConfiguration;
    private final Activate<I> activate;
    private final Store<I> store;

    public static DistillerProvisioningTemplate<KnowledgeDistillerInitialization> knowledge(
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
        TeamRepository requiredTeams = Objects.requireNonNull(teams, "teams");
        WorkspaceRepository requiredWorkspaces = Objects.requireNonNull(workspaces, "workspaces");
        TeamMemberRepository requiredMembers = Objects.requireNonNull(members, "members");
        PrincipalRepository requiredPrincipals = Objects.requireNonNull(principals, "principals");
        AgentTemplateCatalogInitializer requiredTemplateCatalog =
                Objects.requireNonNull(templateCatalog, "templateCatalog");
        AgentTemplateRepository requiredTemplates = Objects.requireNonNull(templates, "templates");
        KnowledgeDistillerRepository requiredDistillers =
                Objects.requireNonNull(distillers, "distillers");
        AgentConfigurationRepository requiredConfigurations =
                Objects.requireNonNull(configurations, "configurations");
        AgentConfigurationApplicationService requiredConfigurationService =
                Objects.requireNonNull(configurationService, "configurationService");
        TransactionExecutor requiredTransactions =
                Objects.requireNonNull(transactions, "transactions");
        TimeProvider requiredTimeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        Store<KnowledgeDistillerInitialization> store = new Store<>() {
            @Override
            public KnowledgeDistillerInitialization initializeIfAbsent(
                    KnowledgeDistillerInitialization candidate) {
                return requiredDistillers.initializeIfAbsent(candidate);
            }

            @Override
            public Optional<KnowledgeDistillerInitialization> find(
                    OrganizationId organizationId, TeamId teamId) {
                return requiredDistillers.findDistillerForTeam(organizationId, teamId);
            }

            @Override
            public KnowledgeDistillerInitialization updateLifecycle(
                    KnowledgeDistillerInitialization initialization) {
                return requiredDistillers.updateLifecycle(initialization);
            }
        };
        return new DistillerProvisioningTemplate<>(
                requiredTeams,
                requiredWorkspaces,
                requiredMembers,
                requiredPrincipals,
                requiredTemplateCatalog,
                requiredTemplates,
                requiredConfigurations,
                requiredConfigurationService,
                requiredTransactions,
                requiredTimeProvider,
                KnowledgeDistillerTemplate.VERSION,
                "knowledgeDistiller",
                "knowledge-distiller@1",
                "system/knowledge-distiller/",
                KnowledgeDistillerInitialization::agentProfile,
                KnowledgeDistillerTemplate::requireDefinition,
                KnowledgeDistillerInitialization::createDefault,
                KnowledgeDistillerInitialization::requireDefaultFor,
                KnowledgeDistillerInitialization::requireActivationConfiguration,
                KnowledgeDistillerInitialization::activate,
                store);
    }

    public static DistillerProvisioningTemplate<SkillDistillerInitialization> skill(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            SkillDistillerRepository distillers,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        TeamRepository requiredTeams = Objects.requireNonNull(teams, "teams");
        WorkspaceRepository requiredWorkspaces = Objects.requireNonNull(workspaces, "workspaces");
        TeamMemberRepository requiredMembers = Objects.requireNonNull(members, "members");
        PrincipalRepository requiredPrincipals = Objects.requireNonNull(principals, "principals");
        AgentTemplateCatalogInitializer requiredTemplateCatalog =
                Objects.requireNonNull(templateCatalog, "templateCatalog");
        AgentTemplateRepository requiredTemplates = Objects.requireNonNull(templates, "templates");
        SkillDistillerRepository requiredDistillers =
                Objects.requireNonNull(distillers, "distillers");
        AgentConfigurationRepository requiredConfigurations =
                Objects.requireNonNull(configurations, "configurations");
        AgentConfigurationApplicationService requiredConfigurationService =
                Objects.requireNonNull(configurationService, "configurationService");
        TransactionExecutor requiredTransactions =
                Objects.requireNonNull(transactions, "transactions");
        TimeProvider requiredTimeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        Store<SkillDistillerInitialization> store = new Store<>() {
            @Override
            public SkillDistillerInitialization initializeIfAbsent(
                    SkillDistillerInitialization candidate) {
                return requiredDistillers.initializeIfAbsent(candidate);
            }

            @Override
            public Optional<SkillDistillerInitialization> find(
                    OrganizationId organizationId, TeamId teamId) {
                return requiredDistillers.findSkillDistillerForTeam(organizationId, teamId);
            }

            @Override
            public SkillDistillerInitialization updateLifecycle(
                    SkillDistillerInitialization initialization) {
                return requiredDistillers.updateLifecycle(initialization);
            }
        };
        return new DistillerProvisioningTemplate<>(
                requiredTeams,
                requiredWorkspaces,
                requiredMembers,
                requiredPrincipals,
                requiredTemplateCatalog,
                requiredTemplates,
                requiredConfigurations,
                requiredConfigurationService,
                requiredTransactions,
                requiredTimeProvider,
                SkillDistillerTemplate.VERSION,
                "skillDistiller",
                "skill-distiller@1",
                "system/skill-distiller/",
                SkillDistillerInitialization::agentProfile,
                SkillDistillerTemplate::requireDefinition,
                SkillDistillerInitialization::createDefault,
                SkillDistillerInitialization::requireDefaultFor,
                SkillDistillerInitialization::requireActivationConfiguration,
                SkillDistillerInitialization::activate,
                store);
    }

    private DistillerProvisioningTemplate(
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMemberRepository members,
            PrincipalRepository principals,
            AgentTemplateCatalogInitializer templateCatalog,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            AgentConfigurationApplicationService configurationService,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            AgentTemplateVersion version,
            String errorPrefix,
            String templateLabel,
            String commandPrefix,
            ProfileOf<I> profileOf,
            DefinitionCheck definitionCheck,
            CreateDefault<I> createDefault,
            RequireDefaultFor<I> requireDefaultFor,
            RequireActivationConfiguration<I> requireActivationConfiguration,
            Activate<I> activate,
            Store<I> store) {
        this.teams = teams;
        this.workspaces = workspaces;
        this.members = members;
        this.principals = principals;
        this.templateCatalog = templateCatalog;
        this.templates = templates;
        this.configurations = configurations;
        this.configurationService = configurationService;
        this.transactions = transactions;
        this.timeProvider = timeProvider;
        this.version = version;
        this.errorPrefix = errorPrefix;
        this.templateLabel = templateLabel;
        this.commandPrefix = commandPrefix;
        this.profileOf = profileOf;
        this.definitionCheck = definitionCheck;
        this.createDefault = createDefault;
        this.requireDefaultFor = requireDefaultFor;
        this.requireActivationConfiguration = requireActivationConfiguration;
        this.activate = activate;
        this.store = store;
    }

    /** Ensures identity, initial model configuration and ACTIVE lifecycle for one Team. */
    public I ensureReady(OrganizationId organizationId, TeamId teamId) {
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

        I current = ensureIdentity(team, workspace, ownerMember, owner);
        if (profileOf.apply(current).status() == AgentProfileStatus.ACTIVE) {
            return current;
        }
        ensureConfiguration(team, owner, current);
        return activate(team, owner);
    }

    private I ensureIdentity(
            Team team, Workspace workspace, TeamMember ownerMember, Principal owner) {
        templateCatalog.initialize(team.organizationId(), owner.id(), timeProvider.now());
        AgentTemplateDefinition template = templates
                .findByVersion(
                        AgentTemplatePublisherScope.organization(team.organizationId()),
                        version)
                .map(definitionCheck::apply)
                .orElseThrow(() -> new DomainValidationException(
                        errorPrefix + ".template",
                        "the built-in " + templateLabel + " template is missing"));
        I candidate = createDefault.apply(
                team, workspace, ownerMember, owner, template, timeProvider.now());
        return transactions.required(() -> requireDefaultFor.apply(
                store.initializeIfAbsent(candidate), team, workspace));
    }

    private void ensureConfiguration(Team team, Principal owner, I distiller) {
        if (configurations
                .findCurrent(team.organizationId(), profileOf.apply(distiller).id())
                .isPresent()) {
            return;
        }
        TeamAccessContext access = new TeamAccessContext(owner, true);
        var selectable = configurationService.selectable(
                access,
                team.organizationId(),
                team.id(),
                profileOf.apply(distiller).id(),
                AgentExecutionScope.TEAM);
        if (selectable.isEmpty()) {
            throw new DomainValidationException(
                    errorPrefix + ".modelBinding",
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
        String commandCoordinate = commandPrefix
                + profileOf.apply(distiller).id()
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
                    context, team.id(), profileOf.apply(distiller).id(), 0, draft);
        } catch (OptimisticLockConflictException concurrent) {
            if (configurations
                    .findCurrent(team.organizationId(), profileOf.apply(distiller).id())
                    .isEmpty()) {
                throw concurrent;
            }
        }
    }

    private I activate(Team team, Principal owner) {
        I current = store.find(team.organizationId(), team.id())
                .orElseThrow(() -> new DomainValidationException(
                        errorPrefix, "the built-in Distiller was not initialized"));
        if (profileOf.apply(current).status() == AgentProfileStatus.ACTIVE) {
            return current;
        }
        try {
            return transactions.required(() -> {
                I latest = store.find(team.organizationId(), team.id())
                        .orElseThrow();
                if (profileOf.apply(latest).status() == AgentProfileStatus.ACTIVE) {
                    return latest;
                }
                var configuration = configurations
                        .findCurrent(team.organizationId(), profileOf.apply(latest).id())
                        .orElseThrow(() -> new DomainValidationException(
                                errorPrefix + ".configuration",
                                "a current TEAM model configuration is required"
                                        + " before activation"));
                requireActivationConfiguration.apply(latest, configuration);
                return store.updateLifecycle(activate.apply(
                        latest, configuration, owner.id(), timeProvider.now()));
            });
        } catch (OptimisticLockConflictException concurrent) {
            I committed = store.find(team.organizationId(), team.id())
                    .orElseThrow();
            if (profileOf.apply(committed).status() == AgentProfileStatus.ACTIVE) {
                return committed;
            }
            throw concurrent;
        }
    }

    @FunctionalInterface
    private interface ProfileOf<I> {
        AgentProfile apply(I value);
    }

    @FunctionalInterface
    private interface DefinitionCheck {
        AgentTemplateDefinition apply(AgentTemplateDefinition definition);
    }

    @FunctionalInterface
    private interface CreateDefault<I> {
        I apply(
                Team team,
                Workspace workspace,
                TeamMember ownerMember,
                Principal ownerUser,
                AgentTemplateDefinition template,
                UtcTimestamp occurredAt);
    }

    @FunctionalInterface
    private interface RequireDefaultFor<I> {
        I apply(I candidate, Team team, Workspace workspace);
    }

    @FunctionalInterface
    private interface RequireActivationConfiguration<I> {
        AgentConfigurationVersion apply(I value, AgentConfigurationVersion configuration);
    }

    @FunctionalInterface
    private interface Activate<I> {
        I apply(
                I value,
                AgentConfigurationVersion configuration,
                PrincipalId actor,
                UtcTimestamp occurredAt);
    }

    private interface Store<I> {
        I initializeIfAbsent(I candidate);

        Optional<I> find(OrganizationId organizationId, TeamId teamId);

        I updateLifecycle(I initialization);
    }
}
