package io.crewscope.application.command;

import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.event.PendingOutboxEvent;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.shared.DomainEvent;
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
import io.crewscope.domain.shared.id.AggregateId;
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
import io.crewscope.domain.team.TeamScope;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Common command envelope of the Team catalog services (M13-C03): the organization/Team
 * membership guards, the role-grant permission gate, the request hash, the version
 * checks, the event-plus-receipt commit and the mutating-command transaction skeleton.
 * Aggregate-specific construction, key-conflict checks and receipt results stay with
 * each service.
 */
public final class TeamCommandSupport {

    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TimeProvider timeProvider;

    public TeamCommandSupport(
            TeamRepository teams,
            TeamMembershipQuery memberships,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TimeProvider timeProvider) {
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public Principal requireOrganizationUser(
            TeamAccessContext context, OrganizationId organizationId) {
        Principal actor = Objects.requireNonNull(context, "context").actor();
        if (actor.type() != PrincipalType.USER
                || !actor.canAct()
                || !actor.scope().organizationId().equals(organizationId)) {
            throw new PolicyDeniedException("act in this Organization");
        }
        return actor;
    }

    public Team requireTeam(OrganizationId organizationId, TeamId teamId) {
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

    public TeamMember requireActiveMember(Principal actor, Team team, String denialMessage) {
        return memberships.findByTeam(team.organizationId(), team.id()).stream()
                .filter(member -> member.userPrincipalId().equals(actor.id()))
                .filter(TeamMember::canParticipate)
                .findFirst()
                .orElseThrow(() -> new PolicyDeniedException(denialMessage));
    }

    /** Platform administrators bypass; everyone else needs an effective grant of {@code permission}. */
    public static void requireManage(
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            TeamAccessContext context,
            Team team,
            TeamMember member,
            UtcTimestamp now,
            TeamPermission permission,
            String denialMessage) {
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
                .anyMatch(role -> role.permissions().contains(permission));
        if (!allowed) {
            throw new PolicyDeniedException(denialMessage);
        }
    }

    public CommandRequestHash requestHash(
            TeamCommandContext context, String commandType, String... semanticFields) {
        String[] fields = new String[semanticFields.length + 2];
        fields[0] = context.access().actor().id().toString();
        fields[1] = context.causationId().map(UUID::toString).orElse("");
        System.arraycopy(semanticFields, 0, fields, 2, semanticFields.length);
        return CommandRequestHash.sha256(commandType, fields);
    }

    public static void requireNonNegativeVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    public static void requireHeadVersion(
            String aggregateType,
            AggregateId aggregateId,
            long expectedVersion,
            long actualVersion) {
        if (actualVersion != expectedVersion) {
            throw new OptimisticLockConflictException(
                    aggregateType, aggregateId, expectedVersion, actualVersion);
        }
    }

    /**
     * Shared transaction skeleton of the pointer-moving commands: guards, completed-replay,
     * head-version check, reservation, then the aggregate-specific mutation and one-event
     * commit.
     */
    public <A> CommandExecution<A> executeMutation(
            TransactionExecutor transactions,
            TeamCommandContext context,
            TeamId teamId,
            long expectedVersion,
            String commandType,
            CommandRequestHash requestHash,
            String denialMessage,
            MutatingGuard guard,
            AggregateProfile<A> profile,
            BiFunction<OrganizationId, TeamId, A> loader,
            Mutation<A> action) {
        OrganizationId organizationId = context.access().actor().scope().organizationId();
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = requireOrganizationUser(context.access(), organizationId);
            Team team = requireTeam(organizationId, teamId);
            TeamMember member = requireActiveMember(actor, team, denialMessage);
            guard.check(context.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, context.idempotencyKey(),
                            commandType, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            A current = loader.apply(organizationId, teamId);
            requireHeadVersion(
                    profile.aggregateType(),
                    profile.id().apply(current),
                    expectedVersion,
                    profile.version().apply(current));
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    context.idempotencyKey(),
                    commandType,
                    requestHash,
                    commandId,
                    context.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            Emitted<A> emitted = action.apply(new Facts<>(organizationId, actor, current, now));
            return appendCreated(
                    context,
                    commandId,
                    emitted.aggregate(),
                    profile,
                    emitted.eventType().value(),
                    emitted.payload(),
                    now);
        });
    }

    /** Appends the aggregate's event, completes the receipt and returns the execution. */
    public <A> CommandExecution<A> appendCreated(
            TeamCommandContext context,
            UUID commandId,
            A aggregate,
            AggregateProfile<A> profile,
            String eventType,
            DomainEvent payload,
            UtcTimestamp occurredAt) {
        TeamScope scope = profile.scope().apply(aggregate);
        return appendCreated(
                context,
                commandId,
                aggregate,
                scope.organizationId(),
                scope.teamId(),
                profile.aggregateType(),
                profile.id().apply(aggregate),
                profile.version().apply(aggregate),
                eventType,
                payload,
                occurredAt);
    }

    /** Appends one event and completes the receipt for explicitly addressed aggregates. */
    public <A> CommandExecution<A> appendCreated(
            TeamCommandContext context,
            UUID commandId,
            A aggregate,
            OrganizationId organizationId,
            TeamId teamId,
            String aggregateType,
            AggregateId aggregateId,
            long aggregateVersion,
            String eventType,
            DomainEvent payload,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event =
                new DomainEventEnvelope<>(
                        eventId,
                        EventType.from(eventType),
                        SchemaVersion.V1,
                        organizationId,
                        Optional.of(teamId),
                        Optional.empty(),
                        AggregateReference.of(aggregateType, aggregateId),
                        aggregateVersion,
                        EventActor.principal(EventActorType.USER, context.access().actor().id()),
                        context.correlationId(),
                        context.causationId(),
                        Optional.of(context.idempotencyKey().value()),
                        occurredAt,
                        payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, aggregateVersion,
                        context.correlationId());
        receipts.complete(organizationId, context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(aggregate, receipt);
    }

    /**
     * Zero-event write: the receipt anchors on the command id — the only durable fact a
     * zero-event write can name without inventing a stream event, unique per command so
     * replayed saves never collide on the anchor index.
     */
    public <A> CommandExecution<A> completeWithoutEvent(
            TeamCommandContext context,
            UUID commandId,
            A aggregate,
            long aggregateVersion,
            UtcTimestamp occurredAt) {
        CommandReceipt receipt =
                new CommandReceipt(commandId, commandId, aggregateVersion,
                        context.correlationId());
        receipts.complete(
                context.access().actor().scope().organizationId(),
                context.idempotencyKey(),
                receipt,
                occurredAt);
        return CommandExecution.completed(aggregate, receipt);
    }

    /** Identity and version accessors that let one skeleton address both catalog aggregates. */
    public record AggregateProfile<A>(
            String aggregateType,
            Function<A, AggregateId> id,
            Function<A, TeamScope> scope,
            Function<A, Long> version) {
        public AggregateProfile {
            Objects.requireNonNull(aggregateType, "aggregateType");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(version, "version");
        }
    }

    /** The authorized facts a mutating action may use. */
    public record Facts<A>(
            OrganizationId organizationId,
            Principal actor,
            A aggregate,
            UtcTimestamp now) {}

    /** The single domain event a mutating command commits with its result. */
    public record Emitted<A>(A aggregate, EventType eventType, DomainEvent payload) {}

    @FunctionalInterface
    public interface MutatingGuard {
        void check(
                TeamAccessContext context, Team team, TeamMember member, UtcTimestamp now);
    }

    @FunctionalInterface
    public interface Mutation<A> {
        Emitted<A> apply(Facts<A> facts);
    }
}
