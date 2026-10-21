package io.crewscope.application.knowledge;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.command.TeamCommandSupport;
import io.crewscope.application.command.TeamCommandSupport.AggregateProfile;
import io.crewscope.application.command.TeamCommandSupport.Mutation;
import io.crewscope.application.event.DomainEventStore;
import io.crewscope.application.event.OutboxRepository;
import io.crewscope.application.retrieval.KnowledgeIndexJobService;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamCommandContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeDisclosurePolicy;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryPublication;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.knowledge.KnowledgeVersionUnchangedException;
import io.crewscope.domain.knowledge.event.KnowledgeEntryCreated;
import io.crewscope.domain.knowledge.event.KnowledgeEntryDeleted;
import io.crewscope.domain.knowledge.event.KnowledgeVersionPublished;
import io.crewscope.domain.knowledge.event.KnowledgeVersionRetired;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamPermission;
import io.crewscope.domain.team.TeamScope;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authorized Team Knowledge management boundary (A02a): manual entry lifecycle commands
 * with strong ETag concurrency, idempotent receipts and the four frozen domain events.
 * Reads require active Team membership; every command additionally requires
 * {@link TeamPermission#KNOWLEDGE_MANAGE}. Index-side invalidation consumes the events;
 * this service never claims an entry is retrievable. The command envelope is shared with
 * the Skill catalog (M13-C03); entry construction stays here.
 */
public final class KnowledgeCommandService {

    private static final String CREATE = "CREATE_KNOWLEDGE_ENTRY";
    private static final String UPDATE_DRAFT = "UPDATE_KNOWLEDGE_DRAFT";
    private static final String PUBLISH = "PUBLISH_KNOWLEDGE_VERSION";
    private static final String ROLLBACK = "ROLLBACK_KNOWLEDGE_VERSION";
    private static final String RETIRE = "RETIRE_KNOWLEDGE_VERSION";
    private static final String DELETE = "DELETE_KNOWLEDGE_ENTRY";
    private static final String REFRESH_INDEX = "REFRESH_KNOWLEDGE_INDEX";
    private static final String AGGREGATE_TYPE = "KNOWLEDGE_ENTRY";
    private static final String READ_DENIAL = "access this Team's knowledge";
    private static final String MANAGE_DENIAL = "manage this Team's knowledge";
    private static final AggregateProfile<KnowledgeEntry> ENTRY_PROFILE =
            new AggregateProfile<>(
                    AGGREGATE_TYPE, KnowledgeEntry::id, KnowledgeEntry::scope,
                    KnowledgeEntry::version);

    private final KnowledgeRepository repository;
    private final TeamRoleRepository roles;
    private final MemberRoleRepository grants;
    private final CommandReceiptStore receipts;
    private final TransactionExecutor transactions;
    private final TimeProvider timeProvider;
    private final KnowledgeIndexJobService indexJobs;
    private final TeamCommandSupport support;

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
            TimeProvider timeProvider,
            KnowledgeIndexJobService indexJobs) {
        this.repository = Objects.requireNonNull(repository, "repository");
        TeamRepository requiredTeams = Objects.requireNonNull(teams, "teams");
        TeamMembershipQuery requiredMemberships = Objects.requireNonNull(memberships, "memberships");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.grants = Objects.requireNonNull(grants, "grants");
        DomainEventStore requiredEvents = Objects.requireNonNull(events, "events");
        OutboxRepository requiredOutbox = Objects.requireNonNull(outbox, "outbox");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        TimeProvider requiredTimeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
        this.timeProvider = requiredTimeProvider;
        this.indexJobs = Objects.requireNonNull(indexJobs, "indexJobs");
        this.support = new TeamCommandSupport(
                requiredTeams, requiredMemberships, requiredEvents, requiredOutbox,
                receipts, requiredTimeProvider);
    }

    // ---------------------------------------------------------------- queries

    /** Head detail for management surfaces; the head version is the strong ETag source. */
    public KnowledgeEntry entry(
            TeamAccessContext context,
            OrganizationId organizationId,
            TeamId teamId,
            KnowledgeEntryId entryId) {
        return transactions.required(() -> {
            Principal actor = support.requireOrganizationUser(context, organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            support.requireActiveMember(actor, team, READ_DENIAL);
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
            Principal actor = support.requireOrganizationUser(context, organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            support.requireActiveMember(actor, team, READ_DENIAL);
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
            Principal actor = support.requireOrganizationUser(context, organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            support.requireActiveMember(actor, team, READ_DENIAL);
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
            Principal actor = support.requireOrganizationUser(context, organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            support.requireActiveMember(actor, team, READ_DENIAL);
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
            Principal actor = support.requireOrganizationUser(context, organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            support.requireActiveMember(actor, team, READ_DENIAL);
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
                support.requestHash(
                        trusted,
                        CREATE,
                        teamId.toString(),
                        required.entryKey().value(),
                        required.category().name(),
                        required.title(),
                        required.content());
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = support.requireOrganizationUser(trusted.access(), organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            TeamMember member = support.requireActiveMember(actor, team, READ_DENIAL);
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
            CommandExecution<KnowledgeEntry> execution = support.appendCreated(
                    trusted, commandId, committed, ENTRY_PROFILE,
                    "KNOWLEDGE_ENTRY_CREATED",
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
        TeamCommandSupport.requireNonNegativeVersion(expectedVersion);
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash =
                support.requestHash(
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
            Principal actor = support.requireOrganizationUser(trusted.access(), organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            TeamMember member = support.requireActiveMember(actor, team, READ_DENIAL);
            requireKnowledgeManage(trusted.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, trusted.idempotencyKey(),
                            UPDATE_DRAFT, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            KnowledgeEntry current = requireEntry(organizationId, teamId, entryId);
            TeamCommandSupport.requireHeadVersion(
                    AGGREGATE_TYPE, current.id(), expectedVersion, current.version());
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
            // No domain event by design; the receipt anchors on the command id.
            return support.completeWithoutEvent(
                    trusted, commandId, committed, committed.version(), now);
        });
    }

    /** Publishes the current draft as the next immutable revision and moves the pointer. */
    public CommandExecution<KnowledgeEntry> publish(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion) {
        return mutatingCommand(
                context, teamId, entryId, expectedVersion, PUBLISH, new String[] {}, facts -> {
            // Command-level disclosure gate (A02b, S01 §3.5): distilled content carries
            // private-source material, so it is scanned before the wider audience sees it.
            // Attribution metadata never contains free text, only the draft is scanned.
            facts.aggregate().origin().ifPresent(ignored ->
                    facts.aggregate().draft().ifPresent(draft ->
                            KnowledgeDisclosurePolicy.requireDisclosable(
                                    draft.title(), draft.content())));
            // A no-change publish is refused here, not in the domain: the head cannot
            // see the effective row's digest (that knowledge lives in the version
            // table). Equal digests are otherwise legal since V72 — a rollback
            // re-appends one by design, so only this command face guards against it.
            if (facts.aggregate().effectiveRevision().isPresent()) {
                KnowledgeEntryVersion effective = repository
                        .findVersion(
                                facts.organizationId(),
                                teamId,
                                facts.aggregate().id(),
                                facts.aggregate().effectiveRevision().orElseThrow())
                        .orElseThrow(() -> new AggregateNotFoundException(
                                "KnowledgeEntryVersion", facts.aggregate().id()));
                facts.aggregate().draft().ifPresent(draft -> {
                    if (effective.contentHash().equals(draft.contentHash())) {
                        throw new KnowledgeVersionUnchangedException(
                                facts.aggregate().id(), effective.revision().value());
                    }
                });
            }
            KnowledgeEntryPublication publication =
                    facts.aggregate().publish(facts.actor().id(), facts.now());
            KnowledgeEntry committed = repository.save(
                    publication.entry(), Optional.of(publication.version()));
            return new TeamCommandSupport.Emitted<>(
                    committed,
                    EventType.from("KNOWLEDGE_VERSION_PUBLISHED"),
                    KnowledgeVersionPublished.from(
                            committed.entryKey(), publication.version()));
        });
    }

    /**
     * Activates historical content as a new revision (M13b-A05): the appended version
     * carries the target's title and content verbatim and history itself never changes.
     * The disclosure gate re-runs on the target's bytes — a rollback re-exposes
     * distilled material to the wider audience exactly like a publish, so the same
     * A02b scan applies even though those bytes already passed it once.
     */
    public CommandExecution<KnowledgeEntry> rollback(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion,
            long toRevision) {
        if (toRevision < 1) {
            throw new DomainValidationException(
                    "knowledgeEntry.rollback", "toRevision must be positive");
        }
        return mutatingCommand(
                context, teamId, entryId, expectedVersion, ROLLBACK,
                new String[] {Long.toString(toRevision)},
                facts -> {
            KnowledgeEntryVersion target = repository
                    .findVersion(
                            facts.organizationId(),
                            teamId,
                            facts.aggregate().id(),
                            new KnowledgeEntryRevision(toRevision))
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "KnowledgeEntryVersion", facts.aggregate().id()));
            facts.aggregate().origin().ifPresent(ignored ->
                    KnowledgeDisclosurePolicy.requireDisclosable(
                            target.title(), target.content()));
            KnowledgeEntryPublication publication =
                    facts.aggregate().rollback(target, facts.actor().id(), facts.now());
            KnowledgeEntry committed = repository.save(
                    publication.entry(), Optional.of(publication.version()));
            return new TeamCommandSupport.Emitted<>(
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
        return mutatingCommand(
                context, teamId, entryId, expectedVersion, RETIRE, new String[] {}, facts -> {
            KnowledgeEntry retired =
                    facts.aggregate().retire(facts.actor().id(), facts.now());
            KnowledgeEntryVersion lastEffective = repository
                    .findVersion(
                            facts.organizationId(),
                            teamId,
                            facts.aggregate().id(),
                            retired.lastEffectiveRevision().orElseThrow())
                    .orElseThrow(() -> new AggregateNotFoundException(
                            "KnowledgeEntryVersion", facts.aggregate().id()));
            KnowledgeEntry committed = repository.save(retired, Optional.empty());
            return new TeamCommandSupport.Emitted<>(
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
        return mutatingCommand(
                context, teamId, entryId, expectedVersion, DELETE, new String[] {}, facts -> {
            KnowledgeEntry deleted =
                    facts.aggregate().delete(facts.actor().id(), facts.now());
            KnowledgeEntry committed = repository.save(deleted, Optional.empty());
            return new TeamCommandSupport.Emitted<>(
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

    /**
     * Re-indexes one entry on demand (M13b-A04/R16: the promised per-entry retry). Emits
     * no domain event and takes no {@code If-Match}: the head aggregate is untouched, so
     * the receipt anchors on the command id like the draft update. An empty enqueue
     * result (refresh gate closed, or a live job already draining this entry) still
     * completes — the command's outcome is observable on the entry-filtered job listing.
     */
    public CommandExecution<KnowledgeEntry> refreshEntryIndex(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(entryId, "entryId");
        OrganizationId organizationId = trusted.access().actor().scope().organizationId();
        CommandRequestHash requestHash = support.requestHash(
                trusted, REFRESH_INDEX, teamId.toString(), entryId.toString());
        return transactions.required(() -> {
            UtcTimestamp now = timeProvider.now();
            Principal actor = support.requireOrganizationUser(trusted.access(), organizationId);
            Team team = support.requireTeam(organizationId, teamId);
            TeamMember member = support.requireActiveMember(actor, team, READ_DENIAL);
            requireKnowledgeManage(trusted.access(), team, member, now);
            Optional<CommandReceipt> completed =
                    receipts.findCompleted(organizationId, trusted.idempotencyKey(),
                            REFRESH_INDEX, requestHash);
            if (completed.isPresent()) {
                return CommandExecution.replayed(completed.orElseThrow());
            }
            KnowledgeEntry current = requireEntry(organizationId, teamId, entryId);
            UUID commandId = UUID.randomUUID();
            CommandReservation reservation = receipts.reserve(new CommandReservationRequest(
                    organizationId,
                    trusted.idempotencyKey(),
                    REFRESH_INDEX,
                    requestHash,
                    commandId,
                    trusted.correlationId(),
                    now));
            if (!reservation.acquired()) {
                return CommandExecution.replayed(reservation.receipt().orElseThrow());
            }
            indexJobs.enqueueEntryRefresh(organizationId, teamId, entryId, actor.id());
            // No domain event by design; the receipt anchors on the command id.
            return support.completeWithoutEvent(
                    trusted, commandId, current, current.version(), now);
        });
    }

    // ---------------------------------------------------------------- internals

    /**
     * Shared skeleton for the pointer-moving commands that all emit one event. The
     * trailing command facts carry each command's body semantics (rollback target)
     * so a reused Idempotency-Key with a different body reports idempotency_conflict
     * instead of silently replaying the first receipt.
     */
    private CommandExecution<KnowledgeEntry> mutatingCommand(
            TeamCommandContext context,
            TeamId teamId,
            KnowledgeEntryId entryId,
            long expectedVersion,
            String commandType,
            String[] commandFacts,
            Mutation<KnowledgeEntry> action) {
        TeamCommandContext trusted = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(entryId, "entryId");
        TeamCommandSupport.requireNonNegativeVersion(expectedVersion);
        String[] semanticFields = new String[commandFacts.length + 3];
        semanticFields[0] = teamId.toString();
        semanticFields[1] = entryId.toString();
        semanticFields[2] = Long.toString(expectedVersion);
        System.arraycopy(commandFacts, 0, semanticFields, 3, commandFacts.length);
        CommandRequestHash requestHash = support.requestHash(
                trusted, commandType, semanticFields);
        return support.executeMutation(
                transactions,
                trusted,
                teamId,
                expectedVersion,
                commandType,
                requestHash,
                READ_DENIAL,
                (access, team, member, now) ->
                        requireKnowledgeManage(access, team, member, now),
                ENTRY_PROFILE,
                (organizationId, ignored) -> requireEntry(organizationId, teamId, entryId),
                action);
    }

    private KnowledgeEntry requireEntry(
            OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
        return repository
                .findById(organizationId, teamId, entryId)
                .orElseThrow(() -> new AggregateNotFoundException("KnowledgeEntry", entryId));
    }

    private void requireKnowledgeManage(
            TeamAccessContext context, Team team, TeamMember member, UtcTimestamp now) {
        TeamCommandSupport.requireManage(
                roles, grants, context, team, member, now,
                TeamPermission.KNOWLEDGE_MANAGE, MANAGE_DENIAL);
    }
}
