package io.crewscope.application.agent;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.SelectableModelCatalogQuery;
import io.crewscope.application.model.SelectableModelCatalogService;
import io.crewscope.application.model.SelectableModelOption;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurationRevision;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentDirectModelBinding;
import io.crewscope.domain.agent.AgentExecutionAuthorizationFacts;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentExecutionScopeFacts;
import io.crewscope.domain.agent.AgentModelBindingKind;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.ResolvedAgentExecutionConfiguration;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.AggregateReference;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.event.EventActor;
import io.crewscope.domain.shared.event.EventActorType;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.teamobserver.TeamObserverTemplate;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.event.AgentConfigurationChanged;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Authorized Agent configuration catalog, append and execution Preflight boundary. */
public final class AgentConfigurationApplicationService {

    private static final String APPEND = "APPEND_AGENT_CONFIGURATION";
    private static final String AGGREGATE_TYPE = "AGENT_CONFIGURATION";

    private final AgentProfileRepository profiles;
    private final AgentTemplateRepository templates;
    private final AgentConfigurationRepository configurations;
    private final ModelConnectionRepository connections;
    private final ModelCatalogEntryRepository catalogs;
    private final SelectableModelCatalogService selectableModels;
    private final AgentExecutionConfigurationResolver resolver;
    private final AgentModelGovernance governance;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public AgentConfigurationApplicationService(
            AgentProfileRepository profiles,
            AgentTemplateRepository templates,
            AgentConfigurationRepository configurations,
            ModelConnectionRepository connections,
            ModelCatalogEntryRepository catalogs,
            SelectableModelCatalogService selectableModels,
            AgentExecutionConfigurationResolver resolver,
            AgentModelGovernance governance,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.selectableModels = Objects.requireNonNull(selectableModels, "selectableModels");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns the current immutable revision after the same visibility check as Agent detail. */
    public AgentConfigurationVersion current(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId) {
        return transactions.required(() -> {
            requireManagement(context, organizationId, teamId, profileId, timeProvider.now());
            return configurations.findCurrent(organizationId, profileId)
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "AgentConfiguration", profileId));
        });
    }

    /** Computes the exact selectable intersection for a configuration editor. */
    public List<SelectableModelOption> selectable(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId,
            AgentExecutionScope executionScope) {
        return transactions.required(() -> {
            ManagementFacts facts = requireManagement(
                    context, organizationId, teamId, profileId, timeProvider.now());
            if (facts.profile().runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT
                    && executionScope == AgentExecutionScope.TEAM) {
                return List.of();
            }
            AgentConfigurationVersion current = configurations
                    .findCurrent(organizationId, profileId)
                    .orElse(null);
            return selectableModels.findSelectable(new SelectableModelCatalogQuery(
                    organizationId,
                    facts.profile().ownership(),
                    ownerUserPrincipal(facts),
                    Objects.requireNonNull(executionScope, "executionScope"),
                    facts.template(),
                    current == null
                            ? io.crewscope.domain.agent.SafeModelGenerateOptions.defaults()
                            : current.generateOptions(),
                    facts.governance().policyConstraints(),
                    facts.governance().allowedProviderKeys(),
                    facts.governance().allowedCatalogCoordinates(),
                    facts.authorization(),
                    facts.now()));
        });
    }

    /** Appends one configuration revision and fully Preflights every executable binding. */
    public CommandExecution<AgentConfigurationVersion> append(
            TeamCommandContext context,
            TeamId teamId,
            AgentProfileId profileId,
            long expectedRevision,
            AgentConfigurationDraft draft) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        AgentConfigurationDraft requiredDraft = Objects.requireNonNull(draft, "draft");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash = requestHash(
                trusted, teamId, profileId, expectedRevision, requiredDraft);
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            ManagementFacts facts = requireManagement(
                    trusted.access(), organizationId, teamId, profileId, now);
            Optional<CommandReceipt> completed = receipts.findCompleted(
                    organizationId, trusted.idempotencyKey(), APPEND, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }

            Optional<AgentConfigurationVersion> current = configurations.findCurrent(
                    organizationId, profileId);
            long actualRevision = current.map(value -> value.revision().value()).orElse(0L);
            if (actualRevision != expectedRevision) {
                throw new OptimisticLockConflictException(
                        "AgentConfiguration", profileId, expectedRevision, actualRevision);
            }
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    trusted.idempotencyKey(),
                    APPEND,
                    requestHash,
                    commandId,
                    trusted.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }

            AgentConfigurationVersion candidate = createCandidate(
                    facts, current, requiredDraft, now);
            preflightCandidate(facts, candidate);
            AgentConfigurationVersion committed = configurations.append(candidate);
            return complete(trusted, commandId, committed, now);
        });
    }

    /** Resolves a current direct or inherited binding into exact, non-secret runtime evidence. */
    public ResolvedAgentExecutionConfiguration preflight(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId,
            AgentExecutionScope executionScope) {
        return transactions.required(() -> {
            ManagementFacts facts = requireManagement(
                    context, organizationId, teamId, profileId, timeProvider.now());
            AgentConfigurationVersion current = configurations
                    .findCurrent(organizationId, profileId)
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "AgentConfiguration", profileId));
            return resolver.resolve(
                    facts.profile(),
                    facts.template(),
                    current,
                    scopeFacts(executionScope),
                    facts.governance().policyConstraints(),
                    facts.authorization(),
                    facts.now());
        });
    }

    private AgentConfigurationVersion createCandidate(
            ManagementFacts facts,
            Optional<AgentConfigurationVersion> current,
            AgentConfigurationDraft draft,
            UtcTimestamp now) {
        Optional<AgentExecutionModelBinding> personal = binding(
                facts, AgentExecutionScope.PERSONAL, draft.personalModelBinding());
        Optional<AgentExecutionModelBinding> team;
        if (facts.template().allowedExecutionScopes().contains(AgentExecutionScope.TEAM)
                && facts.profile().runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT) {
            if (draft.teamModelBinding().isPresent()) {
                throw new DomainValidationException(
                        "agentConfiguration.teamModelBinding",
                        "a Personal Assistant TEAM binding is fixed by the server");
            }
            team = Optional.of(AgentExecutionModelBinding.orchestrationOnly());
        } else {
            team = binding(facts, AgentExecutionScope.TEAM, draft.teamModelBinding());
        }
        Optional<io.crewscope.domain.shared.id.PrincipalId> ownerUser = ownerUserPrincipal(facts);
        if (current.isEmpty()) {
            return AgentConfigurationVersion.createInitial(
                    facts.profile(),
                    facts.template(),
                    ownerUser,
                    personal,
                    team,
                    draft.supplementalInstructions(),
                    draft.approvedSkillKeys(),
                    draft.memoryPolicy(),
                    draft.budgetPolicy(),
                    facts.governance().policyPack(),
                    draft.generateOptions(),
                    facts.actor().id(),
                    now);
        }
        return current.orElseThrow().appendNext(
                facts.profile(),
                facts.template(),
                personal,
                team,
                draft.supplementalInstructions(),
                draft.approvedSkillKeys(),
                draft.memoryPolicy(),
                draft.budgetPolicy(),
                facts.governance().policyPack(),
                draft.generateOptions(),
                facts.actor().id(),
                now);
    }

    private Optional<AgentExecutionModelBinding> binding(
            ManagementFacts facts,
            AgentExecutionScope scope,
            Optional<AgentModelBindingDraft> draft) {
        boolean allowed = facts.template().allowedExecutionScopes().contains(scope);
        if (!allowed) {
            if (draft.isPresent()) {
                throw new DomainValidationException(
                        "agentConfiguration.modelBinding",
                        "must be absent for an unsupported execution scope");
            }
            return Optional.empty();
        }
        AgentModelBindingDraft value = draft.orElseThrow(() -> new DomainValidationException(
                "agentConfiguration.modelBinding",
                "is required for every supported execution scope"));
        if (value.kind() == AgentModelBindingKind.INHERIT_TEAM_DEFAULT) {
            if (scope != AgentExecutionScope.TEAM) {
                throw new DomainValidationException(
                        "agentConfiguration.modelBinding", "only TEAM may inherit a default");
            }
            return Optional.of(AgentExecutionModelBinding.inheritTeamDefault());
        }
        AgentModelSelection primary = selection(facts, value.primary().orElseThrow());
        Optional<AgentModelSelection> fallback = value.fallback().map(item -> selection(facts, item));
        return Optional.of(AgentExecutionModelBinding.direct(
                scope, new AgentDirectModelBinding(primary, fallback)));
    }

    private AgentModelSelection selection(
            ManagementFacts facts, AgentModelSelectionDraft draft) {
        ModelConnection connection = connections.findById(
                        facts.profile().scope().organizationId(), draft.connectionId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ModelConnection", draft.connectionId()));
        if (!facts.usableConnectionIds().contains(connection.id())) {
            throw new PolicyDeniedException("use this Model Connection for the Agent");
        }
        ModelCatalogEntry catalog = catalogs
                .findByEntryRevision(draft.catalogEntryId(), draft.catalogRevision())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "ModelCatalogEntry", draft.catalogEntryId()));
        if (!facts.governance().allowsProvider(catalog.providerKey())
                || !facts.governance().allowsCatalog(catalog.coordinate())) {
            throw new PolicyDeniedException("select this model for the Agent");
        }
        return AgentModelSelection.capture(connection, catalog);
    }

    private void preflightCandidate(
            ManagementFacts facts, AgentConfigurationVersion configuration) {
        AgentProfile preflightProfile = facts.profile();
        if (preflightProfile.status() == AgentProfileStatus.DISABLED
                && TeamObserverTemplate.isTemplateVersion(
                        preflightProfile.templateVersion())) {
            // Activation Preflight must evaluate the exact version that will be committed, while
            // the durable pair remains DISABLED until every model check succeeds.
            preflightProfile = preflightProfile.activate(facts.actor().id(), facts.now());
        }
        AgentProfile executableProfile = preflightProfile;
        configuration.personalModelBinding()
                .filter(binding -> binding.kind() != AgentModelBindingKind.ORCHESTRATION_ONLY)
                .ifPresent(binding -> resolver.resolve(
                        executableProfile,
                        facts.template(),
                        configuration,
                        scopeFacts(AgentExecutionScope.PERSONAL),
                        facts.governance().policyConstraints(),
                        facts.authorization(),
                        facts.now()));
        configuration.teamModelBinding()
                .filter(binding -> binding.kind() != AgentModelBindingKind.ORCHESTRATION_ONLY)
                .ifPresent(binding -> resolver.resolve(
                        executableProfile,
                        facts.template(),
                        configuration,
                        scopeFacts(AgentExecutionScope.TEAM),
                        facts.governance().policyConstraints(),
                        facts.authorization(),
                        facts.now()));
    }

    private CommandExecution<AgentConfigurationVersion> complete(
            TeamCommandContext context,
            UUID commandId,
            AgentConfigurationVersion configuration,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<AgentConfigurationChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from("AGENT_CONFIGURATION_APPENDED"),
                SchemaVersion.V1,
                configuration.organizationId(),
                configuration.ownership().teamId(),
                Optional.empty(),
                AggregateReference.of(AGGREGATE_TYPE, configuration.agentProfileId()),
                // Configuration revisions are one-based while DomainEvent aggregate versions are
                // zero-based. The initial revision therefore belongs at aggregate version zero.
                configuration.revision().value() - 1,
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                occurredAt,
                AgentConfigurationChanged.from(configuration));
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt = new CommandReceipt(
                commandId,
                eventId,
                configuration.revision().value(),
                context.correlationId());
        receipts.complete(
                configuration.organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(configuration, receipt);
    }

    private ManagementFacts requireManagement(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId,
            UtcTimestamp now) {
        Principal actor = requireOrganizationUser(context, organizationId);
        Team team = requireTeam(organizationId, teamId);
        AgentProfile profile = requireProfile(organizationId, teamId, profileId);
        TeamMember member = requireActiveMember(actor, team);
        switch (profile.ownership().type()) {
            case USER -> {
                if (profile.ownership().ownerMemberId().filter(member.id()::equals).isEmpty()) {
                    throw new PolicyDeniedException("configure this user-owned Agent");
                }
            }
            case TEAM -> requireAgentManage(context, team, member, now);
            case ORGANIZATION -> throw new PolicyDeniedException(
                    "configure an Organization-owned Agent through a Team route");
        }
        AgentTemplateDefinition template = requireExactTemplate(profile);
        List<ModelConnection> usableConnections = usableConnections(actor, team, profile);
        AgentModelGovernanceSnapshot policy = governance.resolve(
                actor, team.id(), profile, usableConnections);
        Set<io.crewscope.domain.model.ModelConnectionId> usableIds = usableConnections.stream()
                .map(ModelConnection::id)
                .collect(Collectors.toUnmodifiableSet());
        AgentExecutionAuthorizationFacts authorization = new AgentExecutionAuthorizationFacts(
                actor.id(), true, true, true, true, true, usableIds);
        return new ManagementFacts(
                actor, team, member, profile, template, usableConnections, usableIds,
                policy, authorization, now);
    }

    private List<ModelConnection> usableConnections(
            Principal actor, Team team, AgentProfile profile) {
        Map<io.crewscope.domain.model.ModelConnectionId, ModelConnection> result =
                new LinkedHashMap<>();
        if (profile.ownership().type() == AgentOwnershipType.USER) {
            connections.findByOwner(ModelConnectionOwner.user(actor))
                    .forEach(value -> result.put(value.id(), value));
        }
        connections.findByOwner(ModelConnectionOwner.team(team))
                .forEach(value -> result.put(value.id(), value));
        connections.findByOwner(ModelConnectionOwner.organization(team.organizationId()))
                .forEach(value -> result.put(value.id(), value));
        return result.values().stream()
                .sorted(Comparator.comparing(value -> value.id().toString()))
                .toList();
    }

    private AgentTemplateDefinition requireExactTemplate(AgentProfile profile) {
        List<AgentTemplateDefinition> matches = new ArrayList<>();
        TeamId teamId = profile.scope().teamId().orElseThrow();
        templates.findByVersion(
                        AgentTemplatePublisherScope.team(
                                profile.scope().organizationId(), teamId),
                        profile.templateVersion())
                .ifPresent(matches::add);
        templates.findByVersion(
                        AgentTemplatePublisherScope.organization(
                                profile.scope().organizationId()),
                        profile.templateVersion())
                .ifPresent(matches::add);
        if (matches.size() != 1) {
            throw new DomainValidationException(
                    "agentTemplate.publisherScope",
                    "the AgentProfile must resolve to one exact template publisher");
        }
        return matches.get(0);
    }

    private void requireView(TeamAccessContext context, AgentProfile profile) {
        Principal actor = requireOrganizationUser(context, profile.scope().organizationId());
        Team team = requireTeam(
                profile.scope().organizationId(), profile.scope().teamId().orElseThrow());
        if (context.platformAdministrator()) {
            return;
        }
        TeamMember member = requireActiveMember(actor, team);
        if (profile.ownership().type() == AgentOwnershipType.USER
                && profile.ownership().ownerMemberId().filter(member.id()::equals).isEmpty()) {
            throw new PolicyDeniedException("view this user-owned Agent configuration");
        }
    }

    private AgentProfile requireProfile(
            OrganizationId organizationId, TeamId teamId, AgentProfileId profileId) {
        AgentProfile profile = profiles.findById(organizationId, profileId)
                .orElseThrow(() -> new AggregateNotFoundException("AgentProfile", profileId));
        if (profile.scope().teamId().filter(teamId::equals).isEmpty()) {
            throw new AggregateNotFoundException("AgentProfile", profileId);
        }
        return profile;
    }

    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        if (teams.findUninitializedById(organizationId, teamId).isPresent()) {
            throw new DomainValidationException("team.initializationStatus", "must be READY");
        }
        Team team = teams.findById(organizationId, teamId)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
        if (!team.isActive()) {
            throw new DomainValidationException("team.status", "must be ACTIVE");
        }
        return team;
    }

    private TeamMember requireActiveMember(Principal actor, Team team) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException("access this Team's Agent settings"));
    }

    private void requireAgentManage(
            TeamAccessContext context, Team team, TeamMember member, UtcTimestamp now) {
        if (context.platformAdministrator()) {
            return;
        }
        Map<TeamRoleId, TeamRole> rolesById = roles
                .findByTeam(team.organizationId(), team.id()).stream()
                .collect(Collectors.toMap(TeamRole::id, Function.identity()));
        boolean allowed = grants.findByMember(team.organizationId(), member.id()).stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(now))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .map(grant -> rolesById.get(grant.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(role -> role.permissions().contains(TeamPermission.AGENT_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("configure Team Agents");
        }
    }

    private static Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        Principal actor = Objects.requireNonNull(context, "context").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    private static Optional<io.crewscope.domain.shared.id.PrincipalId> ownerUserPrincipal(
            ManagementFacts facts) {
        return facts.profile().ownership().type() == AgentOwnershipType.USER
                ? Optional.of(facts.actor().id())
                : Optional.empty();
    }

    private static AgentExecutionScopeFacts scopeFacts(AgentExecutionScope scope) {
        AgentExecutionScope required = Objects.requireNonNull(scope, "executionScope");
        return required == AgentExecutionScope.TEAM
                ? new AgentExecutionScopeFacts(true, false, false, false)
                : new AgentExecutionScopeFacts(false, false, false, false);
    }

    private static CommandRequestHash requestHash(
            TeamCommandContext context,
            TeamId teamId,
            AgentProfileId profileId,
            long expectedRevision,
            AgentConfigurationDraft draft) {
        return CommandRequestHash.sha256(
                APPEND,
                context.access().actor().id().toString(),
                teamId.toString(),
                profileId.toString(),
                Long.toString(expectedRevision),
                bindingHash(draft.personalModelBinding()),
                bindingHash(draft.teamModelBinding()),
                draft.supplementalInstructions().orElse(""),
                draft.approvedSkillKeys().stream().sorted().collect(Collectors.joining(",")),
                draft.memoryPolicy().map(Object::toString).orElse(""),
                draft.budgetPolicy().map(Object::toString).orElse(""),
                draft.generateOptions().toString());
    }

    private static String bindingHash(Optional<AgentModelBindingDraft> binding) {
        return binding.map(value -> value.kind().name()
                        + ":" + value.primary().map(Object::toString).orElse("")
                        + ":" + value.fallback().map(Object::toString).orElse(""))
                .orElse("");
    }

    private record ManagementFacts(
            Principal actor,
            Team team,
            TeamMember member,
            AgentProfile profile,
            AgentTemplateDefinition template,
            List<ModelConnection> usableConnections,
            Set<io.crewscope.domain.model.ModelConnectionId> usableConnectionIds,
            AgentModelGovernanceSnapshot governance,
            AgentExecutionAuthorizationFacts authorization,
            UtcTimestamp now) {}
}
