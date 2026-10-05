package io.crewscope.application.skill;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
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
import io.crewscope.domain.knowledge.KnowledgeDisclosurePolicy;
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
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillDisabledException;
import io.crewscope.domain.skill.TeamSkillDraft;
import io.crewscope.domain.skill.TeamSkillDisclosureViolationException;
import io.crewscope.domain.skill.TeamSkillId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.skill.TeamSkillPublication;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillVersion;
import io.crewscope.domain.skill.TeamSkillVersionUnchangedException;
import io.crewscope.domain.skill.event.TeamSkillCreated;
import io.crewscope.domain.skill.event.TeamSkillDisabled;
import io.crewscope.domain.skill.event.TeamSkillVersionPublished;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Authorized Team Skill catalog boundary (A03a): manual skill lifecycle commands with
 * strong ETag concurrency, idempotent receipts and the three frozen domain events.
 * Reads require active Team membership and stay available even while the write side is
 * switched off; every write command additionally requires
 * {@link TeamPermission#SKILL_MANAGE} and the {@code crewscope.skill.enabled} switch.
 * Publishing scans the whole document unconditionally — a Skill is an instruction
 * surface for every later execution of the Team, so it is a stricter gate than the
 * A02 origin-only knowledge scan (S01 §3.8).
 */
public final class TeamSkillCommandService {

    private static final String CREATE = "CREATE_TEAM_SKILL";
    private static final String UPDATE_DRAFT = "UPDATE_TEAM_SKILL_DRAFT";
    private static final String PUBLISH = "PUBLISH_TEAM_SKILL_VERSION";
    private static final String DISABLE = "DISABLE_TEAM_SKILL";
    private static final String ROLLBACK = "ROLLBACK_TEAM_SKILL";
    private static final String AGGREGATE_TYPE = "TEAM_SKILL";

    private final TeamSkillRepository repository;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final boolean skillEnabled;

    public TeamSkillCommandService(
            TeamSkillRepository repository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider,
            boolean skillEnabled) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.events = Objects.requireNonNull(events, "events");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.skillEnabled = skillEnabled;
    }

    // ---------------------------------------------------------------- queries

    /** Head detail for management surfaces; the head version is the strong ETag source. */
    public TeamSkill skill(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            return repository
                    .findById(organizationId, teamId, Objects.requireNonNull(skillId, "skillId"))
                    .orElseThrow(() -> new AggregateNotFoundException("TeamSkill", skillId));
        });
    }

    /** One keyset page of the Team catalog listing ordered by skill key ascending. */
    public TeamSkillPage teamListing(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillFilter filter,
            TeamSkillPageRequest pageRequest) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            return repository.findByTeam(
                    organizationId,
                    teamId,
                    Objects.requireNonNull(filter, "filter"),
                    Objects.requireNonNull(pageRequest, "pageRequest"));
        });
    }

    /** One keyset page of the skill's immutable version history, oldest first. */
    public TeamSkillVersionPage versionHistory(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillVersionPageRequest pageRequest) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            requireSkill(organizationId, teamId, skillId);
            return repository.findVersionHistory(
                    organizationId, teamId, skillId,
                    Objects.requireNonNull(pageRequest, "pageRequest"));
        });
    }

    /** One immutable historical revision; the content hash is the strong ETag source. */
    public TeamSkillVersion version(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId,
            TeamSkillRevision revision) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            requireSkill(organizationId, teamId, skillId);
            return repository
                    .findVersion(
                            organizationId,
                            teamId,
                            skillId,
                            Objects.requireNonNull(revision, "revision"))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TeamSkillVersion", skillId));
        });
    }

    /**
     * The authoritative effective version; resolves empty unless the head is PUBLISHED.
     * Later executions authorize against exactly this row (ADR-031 §4).
     */
    public TeamSkillVersion effectiveVersion(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            TeamSkillId skillId) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            return repository
                    .findEffectiveVersion(organizationId, teamId,
                            Objects.requireNonNull(skillId, "skillId"))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TeamSkillEffectiveVersion", skillId));
        });
    }

    // ---------------------------------------------------------------- commands

    /** Creates a new DRAFT catalog entry; drafts are saved but never loadable. */
    public CommandExecution<TeamSkill> create(
            TeamCommandContext context, TeamId teamId, CreateTeamSkillCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CreateTeamSkillCommand required = Objects.requireNonNull(command, "command");
        requireSkillEnabled();
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                requestHash(
                        trusted,
                        CREATE,
                        teamId.toString(),
                        required.skillKey().value(),
                        required.content());
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = requireOrganizationUser(trusted.access(), organizationId);
            Team team = requireTeam(organizationId, teamId);
            TeamMember member = requireActiveMember(actor, team);
            requireSkillManage(trusted.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, trusted.idempotencyKey(),
                            CREATE, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    trusted.idempotencyKey(),
                    CREATE,
                    requestHash,
                    commandId,
                    trusted.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            // Key uniqueness is checked after the idempotency gate so a same-key retry with
            // different semantics surfaces as an idempotency conflict, not a key collision.
            repository.findByKey(organizationId, teamId, required.skillKey())
                    .ifPresent(existing -> {
                        throw new TeamSkillKeyConflictException(
                                existing.scope(), existing.skillKey());
                    });
            // A03a authors skills manually; the A03b distillation entry point is the only
            // creator that attaches an origin.
            TeamSkill created = TeamSkill.create(
                    new TeamScope(organizationId, teamId),
                    required.skillKey(),
                    required.content(),
                    Optional.empty(),
                    actor.id(),
                    now);
            TeamSkill committed = repository.create(created);
            CommandExecution<TeamSkill> execution = completed(
                    trusted, commandId, committed,
                    EventType.from("TEAM_SKILL_CREATED"),
                    TeamSkillCreated.from(committed),
                    now);
            receipts.saveResult(new CommandResult(
                    organizationId,
                    trusted.idempotencyKey(),
                    actor.id(),
                    CREATE,
                    teamId,
                    Optional.empty(),
                    CommandResult.ResourceType.TEAM_SKILL,
                    committed.id().value(),
                    committed.version(),
                    execution.receipt(),
                    now));
            return execution;
        });
    }

    /**
     * Replaces the mutable draft. Emits no domain event: a draft never changes the
     * loadable set, so future consumers are unaffected (the A02 exception, kept here).
     */
    public CommandExecution<TeamSkill> updateDraft(
            TeamCommandContext context,
            TeamId teamId,
            TeamSkillId skillId,
            long expectedVersion,
            UpdateTeamSkillDraftCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        UpdateTeamSkillDraftCommand required = Objects.requireNonNull(command, "command");
        requireSkillEnabled();
        Objects.requireNonNull(skillId, "skillId");
        requireNonNegativeVersion(expectedVersion);
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                requestHash(
                        trusted,
                        UPDATE_DRAFT,
                        teamId.toString(),
                        skillId.toString(),
                        Long.toString(expectedVersion),
                        required.content());
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = requireOrganizationUser(trusted.access(), organizationId);
            Team team = requireTeam(organizationId, teamId);
            TeamMember member = requireActiveMember(actor, team);
            requireSkillManage(trusted.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, trusted.idempotencyKey(),
                            UPDATE_DRAFT, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            TeamSkill current = requireSkill(organizationId, teamId, skillId);
            requireHeadVersion(current, expectedVersion);
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    trusted.idempotencyKey(),
                    UPDATE_DRAFT,
                    requestHash,
                    commandId,
                    trusted.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            TeamSkill committed = repository.save(
                    current.updateDraft(required.content(), actor.id(), now),
                    Optional.empty());
            // No domain event by design; the receipt anchors on the command id — the only
            // durable fact a zero-event write can name without inventing a stream event,
            // unique per command so replayed saves never collide on the anchor index.
            CommandReceipt receipt =
                    new CommandReceipt(commandId, commandId, committed.version(),
                            trusted.correlationId());
            receipts.complete(organizationId, trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(committed, receipt);
        });
    }

    /**
     * Publishes the current draft as the next immutable revision and moves the pointer.
     * The whole document is disclosure-scanned unconditionally — a Skill is an
     * instruction surface for every later execution of the Team.
     */
    public CommandExecution<TeamSkill> publish(
            TeamCommandContext context,
            TeamId teamId,
            TeamSkillId skillId,
            long expectedVersion) {
        return mutatingCommand(
                context, teamId, skillId, expectedVersion, PUBLISH, new String[] {}, facts -> {
            Optional<TeamSkillVersion> currentEffective = Optional.empty();
            if (facts.skill().effectiveRevision().isPresent()) {
                currentEffective = Optional.of(repository
                        .findVersion(
                                facts.organizationId(),
                                teamId,
                                facts.skill().id(),
                                facts.skill().effectiveRevision().orElseThrow())
                        .orElseThrow(() -> new AggregateNotFoundException(
                                "TeamSkillVersion", facts.skill().id())));
            }
            TeamSkillDraft draft =
                    facts.skill().draft().orElseThrow(() -> new DomainValidationException(
                            "teamSkill.draft", "must be present to publish"));
            // Unconditional disclosure gate: every byte a later execution would obey is
            // scanned, frontmatter included (S01 §3.8 — stricter than the A02 origin gate).
            KnowledgeDisclosurePolicy.scan(draft.content()).ifPresent(family -> {
                throw new TeamSkillDisclosureViolationException(family);
            });
            // A no-change publish is refused here, not in the domain: the head cannot see
            // the effective row's digest (that knowledge lives in the version table).
            currentEffective.ifPresent(effective -> {
                if (effective.contentHash().equals(draft.contentHash())) {
                    throw new TeamSkillVersionUnchangedException(
                            facts.skill().id(), effective.revision().value());
                }
            });
            TeamSkillPublication publication =
                    facts.skill().publish(facts.actor().id(), facts.now());
            TeamSkill committed = repository.save(
                    publication.skill(), Optional.of(publication.version()));
            return new Emitted<>(
                    committed,
                    EventType.from("TEAM_SKILL_VERSION_PUBLISHED"),
                    TeamSkillVersionPublished.from(
                            committed.skillKey(), publication.version()));
        });
    }

    /**
     * Deactivates the skill; the head keeps its last effective revision and draft as
     * historical evidence, and later executions must not load it.
     */
    public CommandExecution<TeamSkill> disable(
            TeamCommandContext context,
            TeamId teamId,
            TeamSkillId skillId,
            long expectedVersion,
            String reason) {
        return mutatingCommand(
                context, teamId, skillId, expectedVersion, DISABLE,
                new String[] {Objects.toString(reason, "")},
                facts -> {
            TeamSkill committed = repository.save(
                    facts.skill().disable(reason, facts.actor().id(), facts.now()),
                    Optional.empty());
            return new Emitted<>(
                    committed,
                    EventType.from("TEAM_SKILL_DISABLED"),
                    TeamSkillDisabled.from(committed));
        });
    }

    /**
     * Activates historical content as a new revision: the appended version carries the
     * target's document verbatim and history itself never changes (ADR-031 §4).
     */
    public CommandExecution<TeamSkill> rollback(
            TeamCommandContext context,
            TeamId teamId,
            TeamSkillId skillId,
            long expectedVersion,
            long toRevision) {
        if (toRevision < 1) {
            throw new DomainValidationException(
                    "teamSkill.rollback", "toRevision must be positive");
        }
        return mutatingCommand(
                context, teamId, skillId, expectedVersion, ROLLBACK,
                new String[] {Long.toString(toRevision)},
                facts -> {
            TeamSkillVersion target = repository
                    .findVersion(
                            facts.organizationId(),
                            teamId,
                            facts.skill().id(),
                            new TeamSkillRevision(toRevision))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "TeamSkillVersion", facts.skill().id()));
            long rolledBackFrom = facts.skill().effectiveRevision()
                    .map(TeamSkillRevision::value).orElse(0L);
            TeamSkillPublication publication =
                    facts.skill().rollback(target, facts.actor().id(), facts.now());
            TeamSkill committed = repository.save(
                    publication.skill(), Optional.of(publication.version()));
            return new Emitted<>(
                    committed,
                    EventType.from("TEAM_SKILL_VERSION_PUBLISHED"),
                    TeamSkillVersionPublished.fromRollback(
                            committed.skillKey(), publication.version(), rolledBackFrom));
        });
    }

    // ---------------------------------------------------------------- internals

    /**
     * Shared skeleton for the three pointer-moving commands that all emit one event. The
     * trailing command facts carry each command's body semantics (rollback target,
     * disable reason) so a reused Idempotency-Key with a different body reports
     * idempotency_conflict instead of silently replaying the first receipt.
     */
    private CommandExecution<TeamSkill> mutatingCommand(
            TeamCommandContext context,
            TeamId teamId,
            TeamSkillId skillId,
            long expectedVersion,
            String commandType,
            String[] commandFacts,
            Function<CommandFacts, Emitted<TeamSkill>> action) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(skillId, "skillId");
        requireSkillEnabled();
        requireNonNegativeVersion(expectedVersion);
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        String[] semanticFields = new String[commandFacts.length + 3];
        semanticFields[0] = teamId.toString();
        semanticFields[1] = skillId.toString();
        semanticFields[2] = Long.toString(expectedVersion);
        System.arraycopy(commandFacts, 0, semanticFields, 3, commandFacts.length);
        CommandRequestHash requestHash = requestHash(trusted, commandType, semanticFields);
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = requireOrganizationUser(trusted.access(), organizationId);
            Team team = requireTeam(organizationId, teamId);
            TeamMember member = requireActiveMember(actor, team);
            requireSkillManage(trusted.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, trusted.idempotencyKey(),
                            commandType, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            TeamSkill current = requireSkill(organizationId, teamId, skillId);
            requireHeadVersion(current, expectedVersion);
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    trusted.idempotencyKey(),
                    commandType,
                    requestHash,
                    commandId,
                    trusted.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            Emitted<TeamSkill> emitted = action.apply(
                    new CommandFacts(organizationId, actor, current, now));
            return completed(trusted, commandId, emitted.result(),
                    emitted.eventType(), emitted.payload(), now);
        });
    }

    private CommandExecution<TeamSkill> completed(
            TeamCommandContext context,
            UUID commandId,
            TeamSkill skill,
            EventType eventType,
            DomainEvent payload,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event =
                new DomainEventEnvelope<>(
                        eventId,
                        eventType,
                        SchemaVersion.V1,
                        skill.scope().organizationId(),
                        Optional.of(skill.scope().teamId()),
                        Optional.empty(),
                        AggregateReference.of(AGGREGATE_TYPE, skill.id()),
                        // The head's optimistic-lock version is the aggregate version.
                        skill.version(),
                        EventActor.principal(EventActorType.USER, context.access().actor().id()),
                        context.correlationId(),
                        context.causationId(),
                        Optional.of(context.idempotencyKey().value()),
                        occurredAt,
                        payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, skill.version(),
                        context.correlationId());
        receipts.complete(
                skill.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(skill, receipt);
    }

    private TeamSkill requireSkill(
            OrganizationId organizationId, TeamId teamId, TeamSkillId skillId) {
        return repository
                .findById(organizationId, teamId, skillId)
                .orElseThrow(() -> new AggregateNotFoundException("TeamSkill", skillId));
    }

    /** Write-side switch: reads stay available so historical evidence remains viewable. */
    private void requireSkillEnabled() {
        if (!skillEnabled) {
            throw new TeamSkillDisabledException();
        }
    }

    private static void requireHeadVersion(TeamSkill current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "TeamSkill", current.id(), expectedVersion, current.version());
        }
    }

    private static void requireNonNegativeVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
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
                .orElseThrow(() -> new PolicyDeniedException("access this Team's skills"));
    }

    private void requireSkillManage(
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
                .anyMatch(role -> role.permissions().contains(TeamPermission.SKILL_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage this Team's skills");
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

    private static CommandRequestHash requestHash(
            TeamCommandContext context, String commandType, String... semanticFields) {
        String[] fields = new String[semanticFields.length + 2];
        fields[0] = context.access().actor().id().toString();
        fields[1] = context.causationId().map(UUID::toString).orElse("");
        System.arraycopy(semanticFields, 0, fields, 2, semanticFields.length);
        return CommandRequestHash.sha256(commandType, fields);
    }

    private record Emitted<T>(T result, EventType eventType, DomainEvent payload) {}

    private record CommandFacts(
            OrganizationId organizationId,
            Principal actor,
            TeamSkill skill,
            UtcTimestamp now) {}
}
