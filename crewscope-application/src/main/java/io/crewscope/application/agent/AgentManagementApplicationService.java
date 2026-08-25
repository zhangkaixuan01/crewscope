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
import io.crewscope.application.identity.PrincipalRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.team.WorkspaceRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
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
import io.crewscope.domain.shared.id.PrincipalId;
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
import io.crewscope.domain.workspace.Workspace;
import io.crewscope.domain.workspace.event.AgentProfileChanged;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Application boundary for authorized Agent template discovery and Agent lifecycle management. */
public final class AgentManagementApplicationService {

    private static final String AGGREGATE_TYPE = "AGENT_PROFILE";
    private static final String CREATE = "CREATE_AGENT_PROFILE";
    private static final String ACTIVATE = "ACTIVATE_AGENT_PROFILE";
    private static final String DISABLE = "DISABLE_AGENT_PROFILE";
    private static final String ARCHIVE = "ARCHIVE_AGENT_PROFILE";

    private final AgentTemplateRepository templates;
    private final AgentProfileRepository profiles;
    private final AgentConfigurationRepository configurations;
    private final AgentInstanceRepository instances;
    private final PrincipalRepository principals;
    private final TeamRepository teams;
    private final WorkspaceRepository workspaces;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public AgentManagementApplicationService(
            AgentTemplateRepository templates,
            AgentProfileRepository profiles,
            AgentConfigurationRepository configurations,
            AgentInstanceRepository instances,
            PrincipalRepository principals,
            TeamRepository teams,
            WorkspaceRepository workspaces,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.templates = Objects.requireNonNull(templates, "templates");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.configurations = Objects.requireNonNull(configurations, "configurations");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Returns the latest active Organization and Team templates the caller may instantiate. */
    public List<AgentTemplateDefinition> listTemplates(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentOwnershipType ownershipType,
            int offset,
            int limit) {
        requireWindow(offset, limit);
        return transactions.required(() -> {
            requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            AgentOwnership ownership = resolveOwnership(context, team, ownershipType, timeProvider.now());
            List<AgentTemplateDefinition> candidates = new ArrayList<>();
            candidates.addAll(templates.findLatestActivePage(
                    AgentTemplatePublisherScope.organization(organizationId), 0, offset + limit));
            candidates.addAll(templates.findLatestActivePage(
                    AgentTemplatePublisherScope.team(organizationId, teamId), 0, offset + limit));
            return candidates.stream()
                    .filter(template -> canInstantiate(template, ownership))
                    .sorted(Comparator
                            .comparing((AgentTemplateDefinition value) ->
                                    value.templateVersion().key().value())
                            .thenComparing(value -> value.publisherScope().teamId().isPresent()))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        });
    }

    /** Creates a non-default Agent Principal/Profile pair and one durable lifecycle fact. */
    public CommandExecution<AgentProfile> create(
            TeamCommandContext context,
            TeamId teamId,
            CreateAgentRequest request) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        CreateAgentRequest required = Objects.requireNonNull(request, "request");
        requirePublisherScope(trusted.access().actor().scope().organizationId(), requiredTeamId,
                required.templatePublisherScope());
        CommandRequestHash hash = CommandRequestHash.sha256(
                CREATE,
                trusted.access().actor().id().toString(),
                requiredTeamId.toString(),
                required.ownershipType().name(),
                required.templatePublisherScope().teamId().map(Object::toString).orElse("organization"),
                required.templateVersion().toString(),
                required.displayName());
        return execute(
                trusted,
                requiredTeamId,
                CREATE,
                hash,
                (actor, team, now) -> resolveOwnership(
                        trusted.access(), team, required.ownershipType(), now),
                (commandId, actor, team, now) -> {
            AgentOwnership ownership = resolveOwnership(
                    trusted.access(), team, required.ownershipType(), now);
            AgentTemplateDefinition template = templates
                    .findByVersion(required.templatePublisherScope(), required.templateVersion())
                    .orElseThrow(() -> new DomainValidationException(
                            "agentTemplate", "the exact approved template version was not found"));
            template.requireInstantiable(ownership);
            if (TeamObserverTemplate.isTemplateVersion(template.templateVersion())) {
                throw new DomainValidationException(
                        "agentProfile.templateVersion",
                        "the built-in Team Observer is provisioned only by Team initialization");
            }
            if (template.runtimeRole() == AgentRuntimeRole.PERSONAL_ASSISTANT) {
                throw new DomainValidationException(
                        "agentProfile.runtimeRole",
                        "the default Personal Agent is provisioned only by Team membership initialization");
            }
            Workspace workspace = workspaces
                    .findById(team.organizationId(), team.defaultWorkspaceId())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "Workspace", team.defaultWorkspaceId()));
            UtcTimestamp occurredAt = timeProvider.now();
            Principal principal = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(team.organizationId(), team.id()),
                    principalType(template.runtimeRole()),
                    Optional.of(actor.id()),
                    required.displayName(),
                    Optional.empty(),
                    visibility(ownership.type()),
                    occurredAt);
            AgentProfile profile = AgentProfile.createTemplateInstance(
                    AgentProfileId.generate(),
                    workspace,
                    principal,
                    ownership,
                    template,
                    false,
                    actor.id(),
                    occurredAt);
            AgentInstance committed = instances.create(new AgentInstance(principal, profile));
            return complete(
                    trusted,
                    commandId,
                    committed.profile(),
                    "AGENT_PROFILE_CREATED",
                    occurredAt);
                });
    }

    public List<ManagedAgentView> list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            int offset,
            int limit) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            List<AgentProfile> values;
            if (context.platformAdministrator()) {
                values = profiles.findByTeam(organizationId, teamId, offset, limit);
            } else {
                TeamMember member = requireActiveMember(actor, team);
                values = profiles.findVisibleToMember(
                        organizationId, teamId, member.id(), offset, limit);
            }
            return values.stream().map(this::view).toList();
        });
    }

    public ManagedAgentView get(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId) {
        return transactions.required(() -> {
            AgentProfile profile = requireProfile(organizationId, teamId, profileId);
            requireView(context, profile);
            return view(profile);
        });
    }

    public List<AgentConfigurationVersion> configurationHistory(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            AgentProfileId profileId,
            int offset,
            int limit) {
        return transactions.required(() -> {
            AgentProfile profile = requireProfile(organizationId, teamId, profileId);
            requireView(context, profile);
            return configurations.findPage(organizationId, profileId, offset, limit);
        });
    }

    public CommandExecution<AgentProfile> activate(
            TeamCommandContext context, TeamId teamId, AgentProfileId profileId, long expectedVersion) {
        return transition(context, teamId, profileId, expectedVersion, ACTIVATE,
                "AGENT_PROFILE_ACTIVATED", AgentProfile::activate, PrincipalStatus.ACTIVE);
    }

    public CommandExecution<AgentProfile> disable(
            TeamCommandContext context, TeamId teamId, AgentProfileId profileId, long expectedVersion) {
        return transition(context, teamId, profileId, expectedVersion, DISABLE,
                "AGENT_PROFILE_DISABLED", AgentProfile::disable, PrincipalStatus.DISABLED);
    }

    public CommandExecution<AgentProfile> archive(
            TeamCommandContext context, TeamId teamId, AgentProfileId profileId, long expectedVersion) {
        return transition(context, teamId, profileId, expectedVersion, ARCHIVE,
                "AGENT_PROFILE_ARCHIVED", AgentProfile::archive, PrincipalStatus.ARCHIVED);
    }

    private CommandExecution<AgentProfile> transition(
            TeamCommandContext context,
            TeamId teamId,
            AgentProfileId profileId,
            long expectedVersion,
            String commandType,
            String eventType,
            ProfileTransition profileTransition,
            PrincipalStatus targetPrincipalStatus) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        AgentProfileId requiredProfileId = Objects.requireNonNull(profileId, "profileId");
        CommandRequestHash hash = CommandRequestHash.sha256(
                commandType,
                trusted.access().actor().id().toString(),
                requiredTeamId.toString(),
                requiredProfileId.toString(),
                Long.toString(expectedVersion));
        return execute(
                trusted,
                requiredTeamId,
                commandType,
                hash,
                (actor, team, now) -> requireManage(
                        trusted.access(),
                        requireProfile(team.organizationId(), team.id(), requiredProfileId),
                        now),
                (commandId, actor, team, now) -> {
            AgentProfile current = requireProfile(team.organizationId(), team.id(), requiredProfileId);
            requireManage(trusted.access(), current, now);
            if (current.defaultProfile()) {
                throw new DomainValidationException(
                        "agentProfile.defaultProfile",
                        "the TeamMember default Personal Agent lifecycle is platform-managed");
            }
            if (TeamObserverTemplate.isTemplateVersion(current.templateVersion())) {
                throw new DomainValidationException(
                        "agentProfile.templateVersion",
                        "the built-in Team Observer lifecycle uses its configuration Preflight gate");
            }
            if (current.version() != expectedVersion) {
                throw new OptimisticLockConflictException(
                        "AgentProfile", current.id(), expectedVersion, current.version());
            }
            Principal principal = requirePrincipal(current);
            requireSynchronizedCurrentStatus(current, principal);
            AgentProfile updated = profileTransition.apply(current, actor.id(), now);
            Principal updatedPrincipal = principal.transitionTo(targetPrincipalStatus, now);
            AgentInstance committed = instances.updateLifecycle(
                    new AgentInstance(updatedPrincipal, updated));
            return complete(trusted, commandId, committed.profile(), eventType, now);
        });
    }

    private CommandExecution<AgentProfile> execute(
            TeamCommandContext context,
            TeamId teamId,
            String commandType,
            CommandRequestHash requestHash,
            TeamAuthority currentAuthority,
            AuthorizedCommand command) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(
                    context.access(), context.access().actor().scope().organizationId());
            Team team = requireTeam(actor.scope().organizationId(), teamId);
            UtcTimestamp now = timeProvider.now();
            // Current authority is checked before both first execution and Receipt replay.
            currentAuthority.require(actor, team, now);
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    team.organizationId(),
                    context.idempotencyKey(),
                    commandType,
                    requestHash,
                    commandId,
                    context.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            return command.apply(commandId, actor, team, now);
        });
    }

    private CommandExecution<AgentProfile> complete(
            TeamCommandContext context,
            UUID commandId,
            AgentProfile profile,
            String eventType,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<AgentProfileChanged> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                profile.scope().organizationId(),
                profile.scope().teamId(),
                Optional.of(profile.workspaceId()),
                AggregateReference.of(AGGREGATE_TYPE, profile.id()),
                profile.version(),
                EventActor.principal(EventActorType.USER, context.access().actor().id()),
                context.correlationId(),
                context.causationId(),
                Optional.of(context.idempotencyKey().value()),
                occurredAt,
                AgentProfileChanged.from(profile));
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt = new CommandReceipt(
                commandId, eventId, profile.version(), context.correlationId());
        receipts.complete(
                profile.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(profile, receipt);
    }

    private ManagedAgentView view(AgentProfile profile) {
        return new ManagedAgentView(
                requirePrincipal(profile),
                profile,
                configurations.findCurrent(profile.scope().organizationId(), profile.id()));
    }

    private Principal requirePrincipal(AgentProfile profile) {
        return principals
                .findById(profile.scope().organizationId(), profile.agentPrincipalId())
                .orElseThrow(() -> new AggregateNotFoundException(
                        "Principal", profile.agentPrincipalId()));
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
            throw new PolicyDeniedException("view this user-owned Agent");
        }
    }

    private void requireManage(
            TeamAccessContext context, AgentProfile profile, UtcTimestamp now) {
        Principal actor = requireOrganizationUser(context, profile.scope().organizationId());
        Team team = requireTeam(
                profile.scope().organizationId(), profile.scope().teamId().orElseThrow());
        switch (profile.ownership().type()) {
            case USER -> {
                TeamMember member = requireActiveMember(actor, team);
                if (profile.ownership().ownerMemberId().filter(member.id()::equals).isEmpty()) {
                    throw new PolicyDeniedException("manage this user-owned Agent");
                }
            }
            case TEAM -> requireAgentManage(context, team, now);
            case ORGANIZATION -> {
                if (!context.platformAdministrator()) {
                    throw new PolicyDeniedException("manage Organization-owned Agents");
                }
            }
        }
    }

    private AgentOwnership resolveOwnership(
            TeamAccessContext context,
            Team team,
            AgentOwnershipType ownershipType,
            UtcTimestamp now) {
        Principal actor = requireOrganizationUser(context, team.organizationId());
        return switch (Objects.requireNonNull(ownershipType, "ownershipType")) {
            case USER -> AgentOwnership.user(
                    team.organizationId(), team.id(), requireActiveMember(actor, team).id());
            case TEAM -> {
                requireAgentManage(context, team, now);
                yield AgentOwnership.team(team.organizationId(), team.id());
            }
            case ORGANIZATION -> throw new DomainValidationException(
                    "agentOwnership.type",
                    "Organization-owned Agents require an Organization Workspace and are not created in a Team route");
        };
    }

    private void requireAgentManage(TeamAccessContext context, Team team, UtcTimestamp now) {
        if (context.platformAdministrator()) {
            return;
        }
        TeamMember member = requireActiveMember(context.actor(), team);
        Map<TeamRoleId, TeamRole> rolesById = roles.findByTeam(team.organizationId(), team.id()).stream()
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
            throw new PolicyDeniedException("manage Team Agents");
        }
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
                .orElseThrow(() -> new PolicyDeniedException("access this Team's Agents"));
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

    private static void requirePublisherScope(
            OrganizationId organizationId,
            TeamId teamId,
            AgentTemplatePublisherScope publisherScope) {
        boolean teamMismatch = publisherScope.teamId().isPresent()
                && publisherScope.teamId().filter(teamId::equals).isEmpty();
        if (!publisherScope.organizationId().equals(organizationId) || teamMismatch) {
            throw new DomainValidationException(
                    "agentTemplate.publisherScope", "must match the requested Organization and Team");
        }
    }

    private static boolean canInstantiate(
            AgentTemplateDefinition template, AgentOwnership ownership) {
        try {
            template.requireInstantiable(ownership);
            return template.runtimeRole() != AgentRuntimeRole.PERSONAL_ASSISTANT
                    && !TeamObserverTemplate.isTemplateVersion(template.templateVersion());
        } catch (DomainValidationException denied) {
            return false;
        }
    }

    private static PrincipalType principalType(AgentRuntimeRole role) {
        return switch (role) {
            case PERSONAL_ASSISTANT -> PrincipalType.PERSONAL_AGENT;
            case TEAM_COORDINATOR -> PrincipalType.TEAM_AGENT;
            case SPECIALIST -> PrincipalType.SPECIALIST_AGENT;
        };
    }

    private static PrincipalVisibility visibility(AgentOwnershipType type) {
        return switch (type) {
            case USER -> PrincipalVisibility.PRIVATE;
            case TEAM -> PrincipalVisibility.TEAM;
            case ORGANIZATION -> PrincipalVisibility.ORGANIZATION;
        };
    }

    private static void requireSynchronizedCurrentStatus(
            AgentProfile profile, Principal principal) {
        PrincipalStatus expected = switch (profile.status()) {
            case ACTIVE -> PrincipalStatus.ACTIVE;
            case DISABLED -> PrincipalStatus.DISABLED;
            case ARCHIVED -> PrincipalStatus.ARCHIVED;
        };
        if (principal.status() != expected) {
            throw new DomainValidationException(
                    "agentInstance.status",
                    "Agent Principal and AgentProfile lifecycle are not synchronized");
        }
    }

    private static void requireWindow(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100 || offset + limit > 200) {
            throw new DomainValidationException(
                    "agentTemplate.page",
                    "offset must be non-negative, limit at most 100 and window at most 200");
        }
    }

    @FunctionalInterface
    private interface AuthorizedCommand {
        CommandExecution<AgentProfile> apply(
                UUID commandId, Principal actor, Team team, UtcTimestamp occurredAt);
    }

    @FunctionalInterface
    private interface TeamAuthority {
        void require(Principal actor, Team team, UtcTimestamp occurredAt);
    }

    @FunctionalInterface
    private interface ProfileTransition {
        AgentProfile apply(AgentProfile profile, PrincipalId actor, UtcTimestamp occurredAt);
    }
}
