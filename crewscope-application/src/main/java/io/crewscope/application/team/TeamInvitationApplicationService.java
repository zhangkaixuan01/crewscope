package io.crewscope.application.team;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.IdempotencyKey;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainException;
import io.crewscope.domain.shared.error.DomainValidationException;
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
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.MemberRoleStatus;
import io.crewscope.domain.team.RoleScope;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInvitation;
import io.crewscope.domain.team.TeamInvitationId;
import io.crewscope.domain.team.TeamInvitationStatus;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.event.TeamInvitationAccepted;
import io.crewscope.domain.team.event.TeamInvitationCreated;
import io.crewscope.domain.team.event.TeamInvitationRevoked;
import io.crewscope.domain.workspace.Workspace;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Team invitation management, public preview and current-account acceptance use cases. */
public final class TeamInvitationApplicationService {

    private static final String INVITATION_AGGREGATE = "TEAM_INVITATION";
    private static final String CREATE_INVITATION = "CREATE_TEAM_INVITATION";
    private static final String REVOKE_INVITATION = "REVOKE_TEAM_INVITATION";
    private static final String ACCEPT_INVITATION = "ACCEPT_TEAM_INVITATION";

    private final TeamInvitationIssueService issueService;
    private final InvitationTokenDigester digester;
    private final TeamInvitationRepository invitations;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository memberRoles;
    private final WorkspaceRepository workspaces;
    private final DefaultPersonalAgentService defaultPersonalAgents;
    private final TeamInvitationAcceptanceService acceptanceService;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public TeamInvitationApplicationService(
            TeamInvitationIssueService issueService,
            InvitationTokenDigester digester,
            TeamInvitationRepository invitations,
            TeamRepository teams,
            TeamMemberRepository members,
            TeamRoleRepository roles,
            MemberRoleRepository memberRoles,
            WorkspaceRepository workspaces,
            DefaultPersonalAgentService defaultPersonalAgents,
            TeamInvitationAcceptanceService acceptanceService,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
        this.issueService = Objects.requireNonNull(issueService, "issueService");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.members = Objects.requireNonNull(members, "members");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.memberRoles = Objects.requireNonNull(memberRoles, "memberRoles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.defaultPersonalAgents = Objects.requireNonNull(
                defaultPersonalAgents, "defaultPersonalAgents");
        this.acceptanceService = Objects.requireNonNull(acceptanceService, "acceptanceService");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Issues one bearer secret after MEMBER_MANAGE authorization; replays never recover it. */
    public CommandExecution<TeamInvitationIssueResult> create(
            TeamCommandContext context,
            TeamId teamId,
            CreateTeamInvitationCommand command) {
        TeamCommandContext trusted = requireCommandContext(context);
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        CreateTeamInvitationCommand requested = Objects.requireNonNull(command, "command");
        CommandRequestHash hash = CommandRequestHash.sha256(
                CREATE_INVITATION,
                trusted.access().actor().id().toString(),
                requiredTeamId.toString(),
                trusted.causationId().map(UUID::toString).orElse(""),
                requested.targetEmail().map(email -> email.value()).orElse(""),
                requested.targetRole().name(),
                Long.toString(requested.ttl().toSeconds()));
        return execute(
                trusted.access().actor().scope().organizationId(),
                trusted.idempotencyKey(),
                trusted.correlationId(),
                CREATE_INVITATION,
                hash,
                commandId -> {
                    Team team = requireLockedTeam(
                            trusted.access().actor().scope().organizationId(), requiredTeamId);
                    requireManageMembers(trusted.access().actor(), team, timeProvider.now());
                    TeamInvitationIssueResult result = issueService.issue(
                            team,
                            trusted.access().actor(),
                            requested.targetEmail(),
                            requested.targetRole(),
                            requested.ttl());
                    TeamInvitation invitation = result.invitation();
                    return completed(
                            trusted.access().actor(),
                            trusted.idempotencyKey(),
                            trusted.correlationId(),
                            trusted.causationId(),
                            commandId,
                            result,
                            invitation,
                            team,
                            "TEAM_INVITATION_CREATED",
                            new TeamInvitationCreated(
                                    invitation.targetRole(),
                                    invitation.targetEmail().isPresent(),
                                    invitation.expiresAt()));
                });
    }

    /** Lists digest-free invitation facts only for members who can manage Team membership. */
    public TeamInvitationPage list(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            Optional<TeamInvitationCursor> cursor,
            int limit) {
        TeamAccessContext trusted = requireAccessContext(context, organizationId);
        return transactions.required(() -> {
            Team team = requireTeam(organizationId, teamId);
            requireManageMembers(trusted.actor(), team, timeProvider.now());
            return invitations.findByTeam(organizationId, teamId, cursor, limit);
        });
    }

    /** Resolves only token-authorized, non-identifying metadata for the public invitation page. */
    public TeamInvitationPreview preview(InvitationToken token) {
        InvitationTokenDigestView presented = digest(token);
        UtcTimestamp now = timeProvider.now();
        return invitations.findByTokenDigest(presented.digest())
                .map(invitation -> preview(invitation, now))
                .orElseGet(TeamInvitationPreview::unavailable);
    }

    /** Revokes one pending invitation using the Invitation-before-Team lock order. */
    public CommandExecution<TeamInvitation> revoke(
            TeamCommandContext context, TeamId teamId, TeamInvitationId invitationId) {
        TeamCommandContext trusted = requireCommandContext(context);
        TeamId requiredTeamId = Objects.requireNonNull(teamId, "teamId");
        TeamInvitationId requiredInvitationId =
                Objects.requireNonNull(invitationId, "invitationId");
        CommandRequestHash hash = CommandRequestHash.sha256(
                REVOKE_INVITATION,
                trusted.access().actor().id().toString(),
                requiredTeamId.toString(),
                trusted.causationId().map(UUID::toString).orElse(""),
                requiredInvitationId.toString());
        return execute(
                trusted.access().actor().scope().organizationId(),
                trusted.idempotencyKey(),
                trusted.correlationId(),
                REVOKE_INVITATION,
                hash,
                commandId -> {
                    OrganizationId organizationId =
                            trusted.access().actor().scope().organizationId();
                    // Authorize before invitation lookup so unauthorized callers cannot use
                    // 403/404 differences to probe invitation identifiers. Mutation locks still
                    // follow the global Invitation-before-Team order below.
                    requireManageMembers(
                            trusted.access().actor(),
                            requireTeam(organizationId, requiredTeamId),
                            timeProvider.now());
                    TeamInvitation invitation = invitations
                            .lockById(organizationId, requiredInvitationId)
                            .filter(value -> value.scope().teamId().equals(requiredTeamId))
                            .orElseThrow(() -> new AggregateNotFoundException(
                                    "TeamInvitation", requiredInvitationId));
                    Team team = requireLockedTeam(organizationId, requiredTeamId);
                    UtcTimestamp now = timeProvider.now();
                    requireManageMembers(trusted.access().actor(), team, now);
                    if (!invitation.isPendingAt(now)) {
                        throw new TeamInvitationApplicationException(
                                TeamInvitationApplicationFailure.INVITATION_NOT_PENDING);
                    }
                    TeamInvitation committed =
                            invitations.update(invitation.revoke(now), invitation.version());
                    return completed(
                            trusted.access().actor(),
                            trusted.idempotencyKey(),
                            trusted.correlationId(),
                            trusted.causationId(),
                            commandId,
                            committed,
                            committed,
                            team,
                            "TEAM_INVITATION_REVOKED",
                            new TeamInvitationRevoked(
                                    committed.targetRole(), committed.targetEmail().isPresent()));
                });
    }

    /** Accepts for the current Account and atomically commits Membership, role and invitation. */
    public CommandExecution<TeamInvitationAcceptanceResult> accept(
            AuthenticatedInvitationCommandContext context, InvitationToken token) {
        AuthenticatedInvitationCommandContext trusted =
                Objects.requireNonNull(context, "context");
        InvitationTokenDigestView presented = digest(token);
        OrganizationId organizationId = trusted.binding().organizationId();
        CommandRequestHash hash = CommandRequestHash.sha256(
                ACCEPT_INVITATION,
                trusted.account().id().toString(),
                trusted.access().actor().id().toString(),
                trusted.causationId().map(UUID::toString).orElse(""),
                presented.persistenceValue());
        return execute(
                organizationId,
                trusted.idempotencyKey(),
                trusted.correlationId(),
                ACCEPT_INVITATION,
                hash,
                commandId -> acceptInTransaction(trusted, presented, commandId));
    }

    private CommandExecution<TeamInvitationAcceptanceResult> acceptInTransaction(
            AuthenticatedInvitationCommandContext context,
            InvitationTokenDigestView presented,
            UUID commandId) {
        UtcTimestamp now = timeProvider.now();
        TeamInvitation invitation = invitations.lockByTokenDigest(presented.digest())
                .filter(value -> value.scope().organizationId().equals(
                        context.binding().organizationId()))
                .filter(value -> value.isPendingAt(now))
                .orElseThrow(TeamInvitationApplicationService::invalidInvitation);
        Team team = teams
                .lockById(invitation.scope().organizationId(), invitation.scope().teamId())
                .filter(Team::isActive)
                .orElseThrow(TeamInvitationApplicationService::invalidInvitation);
        Optional<TeamMember> existing = members.findByTeamAndUserPrincipalId(
                invitation.scope().organizationId(),
                invitation.scope().teamId(),
                context.access().actor().id());
        TeamInvitationAcceptancePlan plan;
        try {
            plan = acceptanceService.planAcceptance(
                    invitation,
                    presented.digest(),
                    context.account(),
                    context.binding(),
                    team,
                    context.access().actor(),
                    existing,
                    TeamMemberId.generate(),
                    now);
        } catch (DomainException invalid) {
            throw invalidInvitation();
        }
        TeamRole targetRole = roles.findByTeam(team.organizationId(), team.id()).stream()
                .filter(role -> role.isBuiltIn(plan.targetRole()))
                .filter(TeamRole::isGrantable)
                .findFirst()
                .orElseThrow(TeamInvitationApplicationService::invalidInvitation);
        // Resolve the complete authorization target before writing Membership state so a missing
        // or disabled built-in role fails without relying on transaction rollback for cleanup.
        TeamMember membership = switch (plan.membershipDisposition()) {
            case CREATED -> members.create(plan.membership());
            case ACTIVATED -> members.update(plan.membership());
            case REUSED -> plan.membership();
        };
        boolean grantCreated = ensureRoleGrant(
                membership, targetRole, invitation.invitedByPrincipalId(), now);
        Workspace workspace = workspaces
                .findById(team.organizationId(), team.defaultWorkspaceId())
                .orElseThrow(TeamInvitationApplicationService::invalidInvitation);
        defaultPersonalAgents.ensureDefault(membership, workspace, context.access().actor());
        TeamInvitation committed =
                invitations.update(plan.invitation(), invitation.version());
        TeamInvitationAcceptanceResult result = new TeamInvitationAcceptanceResult(
                committed, membership, plan.membershipDisposition(), grantCreated);
        return completed(
                context.access().actor(),
                context.idempotencyKey(),
                context.correlationId(),
                context.causationId(),
                commandId,
                result,
                committed,
                team,
                "TEAM_INVITATION_ACCEPTED",
                new TeamInvitationAccepted(
                        context.account().id().value(),
                        membership.id().value(),
                        committed.targetRole(),
                        plan.membershipDisposition().eventResult()));
    }

    private boolean ensureRoleGrant(
            TeamMember membership,
            TeamRole role,
            io.crewscope.domain.shared.id.PrincipalId grantedBy,
            UtcTimestamp now) {
        var matching = memberRoles
                .findByMember(membership.scope().organizationId(), membership.id())
                .stream()
                .filter(grant -> grant.teamRoleId().equals(role.id()))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .toList();
        if (matching.stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .anyMatch(grant -> grant.isEffectiveAt(now))) {
            return false;
        }
        matching.stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.expiresAt()
                        .filter(expiry -> now.compareTo(expiry) >= 0)
                        .isPresent())
                .forEach(grant -> memberRoles.update(grant.expire(now)));
        memberRoles.create(MemberRole.grant(
                MemberRoleId.generate(),
                membership,
                role,
                RoleScope.team(),
                grantedBy,
                now,
                now,
                Optional.empty()));
        return true;
    }

    private TeamInvitationPreview preview(TeamInvitation invitation, UtcTimestamp now) {
        if (invitation.status() == TeamInvitationStatus.EXPIRED
                || (invitation.status() == TeamInvitationStatus.PENDING
                        && now.compareTo(invitation.expiresAt()) >= 0)) {
            return TeamInvitationPreview.expired();
        }
        if (!invitation.isPendingAt(now)) {
            return TeamInvitationPreview.unavailable();
        }
        return teams.findById(invitation.scope().organizationId(), invitation.scope().teamId())
                .filter(Team::isActive)
                .map(team -> TeamInvitationPreview.available(
                        invitation.id(),
                        team.name(),
                        invitation.targetRole(),
                        invitation.expiresAt(),
                        invitation.targetEmail().isPresent()))
                .orElseGet(TeamInvitationPreview::unavailable);
    }

    private <T> CommandExecution<T> execute(
            OrganizationId organizationId,
            IdempotencyKey idempotencyKey,
            UUID correlationId,
            String commandType,
            CommandRequestHash requestHash,
            Function<UUID, CommandExecution<T>> command) {
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    idempotencyKey,
                    commandType,
                    requestHash,
                    commandId,
                    correlationId,
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            return command.apply(commandId);
        });
    }

    private <T> CommandExecution<T> completed(
            Principal actor,
            IdempotencyKey idempotencyKey,
            UUID correlationId,
            Optional<UUID> causationId,
            UUID commandId,
            T result,
            TeamInvitation invitation,
            Team team,
            String eventType,
            DomainEvent payload) {
        UtcTimestamp occurredAt = timeProvider.now();
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event = new DomainEventEnvelope<>(
                eventId,
                EventType.from(eventType),
                SchemaVersion.V1,
                invitation.scope().organizationId(),
                Optional.of(invitation.scope().teamId()),
                Optional.of(team.defaultWorkspaceId()),
                AggregateReference.of(INVITATION_AGGREGATE, invitation.id()),
                invitation.version(),
                EventActor.principal(EventActorType.USER, actor.id()),
                correlationId,
                causationId,
                Optional.of(idempotencyKey.value()),
                occurredAt,
                payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, invitation.version(), correlationId);
        receipts.complete(
                invitation.scope().organizationId(), idempotencyKey, receipt, occurredAt);
        return CommandExecution.completed(result, receipt);
    }

    private void requireManageMembers(Principal actor, Team team, UtcTimestamp now) {
        requireActiveUser(actor, team.organizationId());
        TeamMember member = members
                .findByTeamAndUserPrincipalId(team.organizationId(), team.id(), actor.id())
                .filter(TeamMember::canParticipate)
                .orElseThrow(() -> new PolicyDeniedException("manage Team invitations"));
        Map<TeamRoleId, TeamRole> currentRoles = roles.findByTeam(team.organizationId(), team.id())
                .stream()
                .collect(Collectors.toMap(TeamRole::id, Function.identity()));
        boolean allowed = memberRoles
                .findByMember(team.organizationId(), member.id())
                .stream()
                .filter(grant -> grant.status() == MemberRoleStatus.ACTIVE)
                .filter(grant -> grant.isEffectiveAt(now))
                .filter(grant -> grant.roleScope().equals(RoleScope.team()))
                .map(grant -> currentRoles.get(grant.teamRoleId()))
                .filter(Objects::nonNull)
                .filter(TeamRole::isGrantable)
                .anyMatch(role -> role.permissions().contains(TeamPermission.MEMBER_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage Team invitations");
        }
    }

    private Team requireTeam(OrganizationId organizationId, TeamId teamId) {
        return teams.findById(organizationId, teamId)
                .filter(Team::isActive)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    }

    private Team requireLockedTeam(OrganizationId organizationId, TeamId teamId) {
        return teams.lockById(organizationId, teamId)
                .filter(Team::isActive)
                .orElseThrow(() -> new AggregateNotFoundException("Team", teamId));
    }

    private InvitationTokenDigestView digest(InvitationToken token) {
        var digest = digester.digest(Objects.requireNonNull(token, "token"));
        return new InvitationTokenDigestView(digest, digest.valueForPersistence());
    }

    private static TeamCommandContext requireCommandContext(TeamCommandContext context) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        requireActiveUser(
                trusted.access().actor(), trusted.access().actor().scope().organizationId());
        return trusted;
    }

    private static TeamAccessContext requireAccessContext(
            TeamAccessContext context, OrganizationId organizationId) {
        TeamAccessContext trusted = Objects.requireNonNull(context, "context");
        requireActiveUser(trusted.actor(), Objects.requireNonNull(organizationId, "organizationId"));
        return trusted;
    }

    private static void requireActiveUser(Principal actor, OrganizationId organizationId) {
        Principal required = Objects.requireNonNull(actor, "actor");
        if (required.type() != PrincipalType.USER
                || !required.canAct()
                || required.scope().teamId().isPresent()
                || !required.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("manage Team invitations");
        }
    }

    private static TeamInvitationApplicationException invalidInvitation() {
        return new TeamInvitationApplicationException(
                TeamInvitationApplicationFailure.INVALID_INVITATION);
    }

    private record InvitationTokenDigestView(
            io.crewscope.domain.team.InvitationTokenDigest digest, String persistenceValue) {}
}
