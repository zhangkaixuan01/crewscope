package io.crewscope.application.knowledge;

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
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeDisclosurePolicy;
import io.crewscope.domain.knowledge.KnowledgeEntryPublication;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.knowledge.event.KnowledgeEntryCreated;
import io.crewscope.domain.knowledge.event.KnowledgeEntryDeleted;
import io.crewscope.domain.knowledge.event.KnowledgeVersionPublished;
import io.crewscope.domain.knowledge.event.KnowledgeVersionRetired;
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
 * Authorized Team Knowledge management boundary (A02a): manual entry lifecycle commands
 * with strong ETag concurrency, idempotent receipts and the four frozen domain events.
 * Reads require active Team membership; every command additionally requires
 * {@link TeamPermission#KNOWLEDGE_MANAGE}. Index-side invalidation consumes the events;
 * this service never claims an entry is retrievable.
 */
public final class KnowledgeCommandService {

    private static final String CREATE = "CREATE_KNOWLEDGE_ENTRY";
    private static final String UPDATE_DRAFT = "UPDATE_KNOWLEDGE_DRAFT";
    private static final String PUBLISH = "PUBLISH_KNOWLEDGE_VERSION";
    private static final String RETIRE = "RETIRE_KNOWLEDGE_VERSION";
    private static final String DELETE = "DELETE_KNOWLEDGE_ENTRY";
    private static final String AGGREGATE_TYPE = "KNOWLEDGE_ENTRY";

    private final KnowledgeRepository repository;
    private final TeamRepository teams;
    private final TeamMembershipQuery memberships;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final DomainEventStore events;
    private final OutboxRepository outbox;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;

    public KnowledgeCommandService(
            KnowledgeRepository repository,
            TeamRepository teams,
            TeamMembershipQuery memberships,
            TeamRoleRepository roles,
            MemberRoleRepository grants,
            DomainEventStore events,
            OutboxRepository outbox,
            CommandReceiptStore receipts,
            TransactionExecutor transactions,
            TimeProvider timeProvider) {
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
    }

    // ---------------------------------------------------------------- queries

    /** Head detail for management surfaces; the head version is the strong ETag source. */
    public KnowledgeEntry entry(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            return repository
                    .findById(organizationId, teamId, Objects.requireNonNull(entryId, "entryId"))
                    .orElseThrow(() -> new AggregateNotFoundException("KnowledgeEntry", entryId));
        });
    }

    /** One keyset page of the Team listing ordered by entry key ascending. */
    public KnowledgeEntryPage teamListing(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryFilter filter,
            KnowledgeEntryPageRequest pageRequest) {
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

    /** One keyset page of the entry's immutable version history, oldest first. */
    public KnowledgeEntryVersionPage versionHistory(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeVersionPageRequest pageRequest) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            requireEntry(organizationId, teamId, entryId);
            return repository.findVersionHistory(
                    organizationId, teamId, entryId,
                    Objects.requireNonNull(pageRequest, "pageRequest"));
        });
    }

    /** One immutable historical revision; the content hash is the strong ETag source. */
    public KnowledgeEntryVersion version(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId,
            KnowledgeEntryRevision revision) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            requireEntry(organizationId, teamId, entryId);
            return repository
                    .findVersion(
                            organizationId,
                            teamId,
                            entryId,
                            Objects.requireNonNull(revision, "revision"))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "KnowledgeEntryVersion", entryId));
        });
    }

    /**
     * The authoritative effective version; resolves empty unless the head is PUBLISHED,
     * so the read is itself the invalidation protocol's first half (ADR-030 §2).
     */
    public KnowledgeEntryVersion effectiveVersion(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId) {
        return transactions.required(() -> {
            Principal actor = requireOrganizationUser(context, organizationId);
            Team team = requireTeam(organizationId, teamId);
            requireActiveMember(actor, team);
            return repository
                    .findEffectiveVersion(organizationId, teamId,
                            Objects.requireNonNull(entryId, "entryId"))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "KnowledgeEntryEffectiveVersion", entryId));
        });
    }

    // ---------------------------------------------------------------- commands

    /** Creates a new DRAFT entry; drafts are saved but never retrievable. */
    public CommandExecution<KnowledgeEntry> create(
            TeamCommandContext context, TeamId teamId, CreateKnowledgeEntryCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        CreateKnowledgeEntryCommand required = Objects.requireNonNull(command, "command");
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                requestHash(
                        trusted,
                        CREATE,
                        teamId.toString(),
                        required.entryKey().value(),
                        required.category().name(),
                        required.title(),
                        required.content());
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = requireOrganizationUser(trusted.access(), organizationId);
            Team team = requireTeam(organizationId, teamId);
            TeamMember member = requireActiveMember(actor, team);
            requireKnowledgeManage(trusted.access(), team, member, now);
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
            repository.findByKey(organizationId, teamId, required.entryKey())
                    .ifPresent(existing -> {
                        throw new KnowledgeEntryKeyConflictException(
                                existing.scope(), existing.entryKey());
                    });
            KnowledgeEntry created = KnowledgeEntry.create(
                    new TeamScope(organizationId, teamId),
                    required.entryKey(),
                    required.category(),
                    required.title(),
                    required.content(),
                    actor.id(),
                    now);
            KnowledgeEntry committed = repository.create(created);
            CommandExecution<KnowledgeEntry> execution = completed(
                    trusted, commandId, committed,
                    EventType.from("KNOWLEDGE_ENTRY_CREATED"),
                    KnowledgeEntryCreated.from(committed),
                    now);
            receipts.saveResult(new CommandResult(
                    organizationId,
                    trusted.idempotencyKey(),
                    actor.id(),
                    CREATE,
                    teamId,
                    Optional.empty(),
                    CommandResult.ResourceType.KNOWLEDGE_ENTRY,
                    committed.id().value(),
                    committed.version(),
                    execution.receipt(),
                    now));
            return execution;
        });
    }

    /**
     * Replaces the mutable draft and optionally reclassifies the entry. Emits no domain
     * event: a draft never changes the retrievable set, so index consumers are unaffected.
     */
    public CommandExecution<KnowledgeEntry> updateDraft(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion,
            UpdateKnowledgeDraftCommand command) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        UpdateKnowledgeDraftCommand required = Objects.requireNonNull(command, "command");
        requireNonNegativeVersion(expectedVersion);
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                requestHash(
                        trusted,
                        UPDATE_DRAFT,
                        teamId.toString(),
                        Objects.requireNonNull(entryId, "entryId").toString(),
                        Long.toString(expectedVersion),
                        required.title(),
                        required.content(),
                        required.category().map(KnowledgeCategory::name).orElse(""));
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = requireOrganizationUser(trusted.access(), organizationId);
            Team team = requireTeam(organizationId, teamId);
            TeamMember member = requireActiveMember(actor, team);
            requireKnowledgeManage(trusted.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, trusted.idempotencyKey(),
                            UPDATE_DRAFT, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            KnowledgeEntry current = requireEntry(organizationId, teamId, entryId);
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
            KnowledgeEntry committed = repository.save(
                    current.updateDraft(
                            required.title(), required.content(), required.category(),
                            actor.id(), now),
                    Optional.empty());
            // No domain event by design; the receipt locates the fact via its stable id.
            CommandReceipt receipt =
                    new CommandReceipt(commandId, committed.id().value(), committed.version(),
                            trusted.correlationId());
            receipts.complete(organizationId, trusted.idempotencyKey(), receipt, now);
            return CommandExecution.completed(committed, receipt);
        });
    }

    /** Publishes the current draft as the next immutable revision and moves the pointer. */
    public CommandExecution<KnowledgeEntry> publish(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion) {
        return mutatingCommand(context, teamId, entryId, expectedVersion, PUBLISH, (facts) -> {
            // Command-level disclosure gate (A02b, S01 §3.5): distilled content carries
            // private-source material, so it is scanned before the wider audience sees it.
            // Attribution metadata never contains free text, only the draft is scanned.
            facts.entry().origin().ifPresent(ignored -> facts.entry().draft().ifPresent(draft ->
                    KnowledgeDisclosurePolicy.requireDisclosable(
                            draft.title(), draft.content())));
            KnowledgeEntryPublication publication =
                    facts.entry().publish(facts.actor().id(), facts.now());
            KnowledgeEntry committed = repository.save(
                    publication.entry(), Optional.of(publication.version()));
            return new Emitted<>(
                    committed,
                    EventType.from("KNOWLEDGE_VERSION_PUBLISHED"),
                    KnowledgeVersionPublished.from(
                            committed.entryKey(), publication.version()));
        });
    }

    /** Retires the effective version; the last effective revision stays for attribution. */
    public CommandExecution<KnowledgeEntry> retire(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion) {
        return mutatingCommand(context, teamId, entryId, expectedVersion, RETIRE, (facts) -> {
            KnowledgeEntry retired = facts.entry().retire(facts.actor().id(), facts.now());
            KnowledgeEntryVersion lastEffective = repository
                    .findVersion(
                            facts.organizationId(),
                            teamId,
                            facts.entry().id(),
                            retired.lastEffectiveRevision().orElseThrow())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "KnowledgeEntryVersion", facts.entry().id()));
            KnowledgeEntry committed = repository.save(retired, Optional.empty());
            return new Emitted<>(
                    committed,
                    EventType.from("KNOWLEDGE_VERSION_RETIRED"),
                    KnowledgeVersionRetired.of(
                            committed.id().value(),
                            committed.entryKey(),
                            retired.lastEffectiveRevision().orElseThrow().value(),
                            lastEffective.contentHash().value()));
        });
    }

    /** Deletes the entry as an irreversible tombstone that can never be published again. */
    public CommandExecution<KnowledgeEntry> delete(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion) {
        return mutatingCommand(context, teamId, entryId, expectedVersion, DELETE, (facts) -> {
            KnowledgeEntry deleted = facts.entry().delete(facts.actor().id(), facts.now());
            KnowledgeEntry committed = repository.save(deleted, Optional.empty());
            return new Emitted<>(
                    committed,
                    EventType.from("KNOWLEDGE_ENTRY_DELETED"),
                    KnowledgeEntryDeleted.of(
                            committed.id().value(),
                            committed.entryKey(),
                            committed.lastEffectiveRevision()
                                    .map(KnowledgeEntryRevision::value)
                                    .orElse(0L)));
        });
    }

    // ---------------------------------------------------------------- internals

    /** Shared skeleton for the three pointer-moving commands that all emit one event. */
    private CommandExecution<KnowledgeEntry> mutatingCommand(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion,
            String commandType,
            Function<CommandFacts, Emitted<KnowledgeEntry>> action) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(entryId, "entryId");
        requireNonNegativeVersion(expectedVersion);
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                requestHash(
                        trusted,
                        commandType,
                        teamId.toString(),
                        entryId.toString(),
                        Long.toString(expectedVersion));
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = requireOrganizationUser(trusted.access(), organizationId);
            Team team = requireTeam(organizationId, teamId);
            TeamMember member = requireActiveMember(actor, team);
            requireKnowledgeManage(trusted.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, trusted.idempotencyKey(),
                            commandType, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            KnowledgeEntry current = requireEntry(organizationId, teamId, entryId);
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
            Emitted<KnowledgeEntry> emitted = action.apply(
                    new CommandFacts(organizationId, actor, current, now));
            return completed(trusted, commandId, emitted.result(),
                    emitted.eventType(), emitted.payload(), now);
        });
    }

    private CommandExecution<KnowledgeEntry> completed(
            TeamCommandContext context,
            UUID commandId,
            KnowledgeEntry entry,
            EventType eventType,
            DomainEvent payload,
            UtcTimestamp occurredAt) {
        UUID eventId = UUID.randomUUID();
        DomainEventEnvelope<DomainEvent> event =
                new DomainEventEnvelope<>(
                        eventId,
                        eventType,
                        SchemaVersion.V1,
                        entry.scope().organizationId(),
                        Optional.of(entry.scope().teamId()),
                        Optional.empty(),
                        AggregateReference.of(AGGREGATE_TYPE, entry.id()),
                        // The head's optimistic-lock version is the aggregate version.
                        entry.version(),
                        EventActor.principal(EventActorType.USER, context.access().actor().id()),
                        context.correlationId(),
                        context.causationId(),
                        Optional.of(context.idempotencyKey().value()),
                        occurredAt,
                        payload);
        events.append(event);
        outbox.enqueue(PendingOutboxEvent.fromDomain(UUID.randomUUID(), event));
        CommandReceipt receipt =
                new CommandReceipt(commandId, eventId, entry.version(),
                        context.correlationId());
        receipts.complete(
                entry.scope().organizationId(), context.idempotencyKey(), receipt, occurredAt);
        return CommandExecution.completed(entry, receipt);
    }

    private KnowledgeEntry requireEntry(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return repository
                .findById(organizationId, teamId, entryId)
                .orElseThrow(() -> new AggregateNotFoundException("KnowledgeEntry", entryId));
    }

    private static void requireHeadVersion(KnowledgeEntry current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new OptimisticLockConflictException(
                    "KnowledgeEntry", current.id(), expectedVersion, current.version());
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
                .orElseThrow(() -> new PolicyDeniedException("access this Team's knowledge"));
    }

    private void requireKnowledgeManage(
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
                .anyMatch(role -> role.permissions().contains(TeamPermission.KNOWLEDGE_MANAGE));
        if (!allowed) {
            throw new PolicyDeniedException("manage this Team's knowledge");
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
            KnowledgeEntry entry,
            UtcTimestamp now) {}
}
