package io.crewscope.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.command.CommandExecution;
import io.crewscope.application.command.CommandReceipt;
import io.crewscope.application.command.CommandRequestHash;
import io.crewscope.application.command.CommandReservation;
import io.crewscope.application.command.CommandReservationRequest;
import io.crewscope.application.command.CommandResult;
import io.crewscope.application.command.CommandReceiptStore;
import io.crewscope.application.command.IdempotencyKey;
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
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryOrigin;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import io.crewscope.domain.knowledge.event.KnowledgeEntryCreated;
import io.crewscope.domain.knowledge.KnowledgeDisclosureViolationException;
import io.crewscope.domain.knowledge.event.KnowledgeEntryDeleted;
import io.crewscope.domain.knowledge.event.KnowledgeVersionPublished;
import io.crewscope.domain.knowledge.event.KnowledgeVersionRetired;
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskExecutionId;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
import io.crewscope.domain.team.TeamScope;
import io.crewscope.domain.team.UninitializedTeam;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Command-face contract for A02a: permission gates, idempotent receipts, the head-version
 * ETag source and the exact four-event lifecycle, including the silent draft replacement.
 */
final class KnowledgeCommandServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-30T08:00:00Z");
    private static final KnowledgeEntryKey ENTRY_KEY = new KnowledgeEntryKey("oncall-runbook");

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor =
            Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Owner",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
    private final TeamInitialization initialization = TeamInitialization.create(actor, "Platform", NOW);
    private final TeamId teamId = initialization.team().id();
    private final InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
    private final Store store = new Store(initialization, actor);
    private final KnowledgeCommandService service =
            new KnowledgeCommandService(
                    repository,
                    store,
                    store,
                    store.rolesView(),
                    store,
                    store,
                    store,
                    store,
                    new DirectTransactionExecutor(),
                    () -> NOW);


    // ------------------------------------------------------------------ lifecycle facts

    @Test
    void createEmitsTheCreatedFactWithCategoryAndSavesTheResult() {
        CommandExecution<KnowledgeEntry> execution = createEntry("create-1", KnowledgeCategory.RUNBOOK);

        KnowledgeEntry entry = execution.result().orElseThrow();
        assertEquals(KnowledgeEntryStatus.DRAFT, entry.status());
        assertEquals(0, entry.version());
        assertEquals(KnowledgeCategory.RUNBOOK, entry.category());

        DomainEventEnvelope<? extends DomainEvent> envelope = singleEvent();
        assertEquals("KNOWLEDGE_ENTRY_CREATED", envelope.eventType().value());
        KnowledgeEntryCreated payload = assertInstanceOf(KnowledgeEntryCreated.class, envelope.payload());
        assertEquals(entry.id().value(), payload.entryId());
        assertEquals("oncall-runbook", payload.entryKey());
        assertEquals("On-call runbook", payload.title());
        assertEquals("RUNBOOK", payload.category());
        assertEquals("KNOWLEDGE_ENTRY", envelope.aggregate().type());
        assertEquals(entry.id().value(), envelope.aggregate().id());
        // The head's optimistic-lock version is the aggregate version (D4).
        assertEquals(0, envelope.aggregateVersion());
        assertEquals(Optional.of(teamId), envelope.teamId());
        assertEquals(Optional.of("create-1"), envelope.idempotencyKey());
        assertEquals(1, store.outbox.size());
        assertEquals(envelope.eventId(), execution.receipt().domainEventId());
        assertEquals(0, execution.receipt().committedVersion());

        CommandResult result = store.findResult(organizationId, IdempotencyKey.from("create-1"), actor.id())
                .orElseThrow();
        assertEquals(CommandResult.ResourceType.KNOWLEDGE_ENTRY, result.resourceType());
        assertEquals(entry.id().value(), result.resourceId());
        assertEquals(Optional.empty(), result.projectId());
        assertEquals(actor.id(), result.actorId());
        assertEquals(0, result.resourceVersion());
    }

    @Test
    void updateDraftEmitsNoEventAndLocatesTheReceiptByTheEntryId() {
        KnowledgeEntry created = createEntry("draft-1", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();

        CommandExecution<KnowledgeEntry> execution = service.updateDraft(
                context("draft-1-edit"),
                teamId,
                created.id(),
                0,
                new UpdateKnowledgeDraftCommand("Revised runbook", "Step zero", Optional.empty()));

        KnowledgeEntry committed = execution.result().orElseThrow();
        assertEquals(1, committed.version());
        assertEquals("Revised runbook", committed.draft().orElseThrow().title());
        assertEquals(KnowledgeCategory.RUNBOOK, committed.category());
        // A draft never changes the retrievable set, so no event and no outbox row (D4).
        assertEquals(1, store.events.size());
        assertEquals(1, store.outbox.size());
        // The event-less receipt points at the stable entry id, not a domain event.
        assertEquals(created.id().value(), execution.receipt().domainEventId());
        assertEquals(1, execution.receipt().committedVersion());
        assertTrue(
                store.findResult(organizationId, IdempotencyKey.from("draft-1-edit"), actor.id()).isEmpty(),
                "updateDraft must not save a command result");
    }

    @Test
    void updateDraftMayReclassifyWithoutMintingAVersion() {
        KnowledgeEntry created = createEntry("draft-2", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();

        CommandExecution<KnowledgeEntry> execution = service.updateDraft(
                context("draft-2-edit"),
                teamId,
                created.id(),
                0,
                new UpdateKnowledgeDraftCommand("On-call runbook", "Step one", Optional.of(KnowledgeCategory.DECISION)));

        KnowledgeEntry committed = execution.result().orElseThrow();
        assertEquals(KnowledgeCategory.DECISION, committed.category());
        assertEquals(0, committed.latestRevision());
        assertTrue(repository.findVersion(organizationId, teamId, created.id(), new KnowledgeEntryRevision(1)).isEmpty());
    }

    @Test
    void publishAppendsAnImmutableVersionAndEmitsThePublishedFact() {
        KnowledgeEntry created = createEntry("publish-1", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();

        CommandExecution<KnowledgeEntry> execution = service.publish(
                context("publish-1-go"), teamId, created.id(), 0);

        KnowledgeEntry committed = execution.result().orElseThrow();
        assertEquals(KnowledgeEntryStatus.PUBLISHED, committed.status());
        assertEquals(1, committed.version());
        assertEquals(1, committed.effectiveRevision().orElseThrow().value());
        assertEquals(1, committed.latestRevision());

        assertEquals(2, store.events.size());
        DomainEventEnvelope<? extends DomainEvent> envelope = store.events.get(1);
        assertEquals("KNOWLEDGE_VERSION_PUBLISHED", envelope.eventType().value());
        KnowledgeVersionPublished payload = assertInstanceOf(KnowledgeVersionPublished.class, envelope.payload());
        assertEquals(1, payload.revision());
        assertEquals("On-call runbook", payload.title());
        assertEquals(
                repository.findVersion(organizationId, teamId, created.id(), new KnowledgeEntryRevision(1))
                        .orElseThrow().contentHash().value(),
                payload.contentHash());
        assertEquals(committed.version(), envelope.aggregateVersion());
        assertEquals(
                new KnowledgeEntryRevision(1),
                service.effectiveVersion(access(), organizationId, teamId, created.id()).revision());
    }

    @Test
    void publishRefusesDistilledContentMatchingACredentialFamily() {
        KnowledgeEntry distilled = repository.create(KnowledgeEntry.createDistilled(
                new TeamScope(organizationId, teamId),
                ENTRY_KEY,
                KnowledgeCategory.RUNBOOK,
                "Deploy lessons",
                "Token was ghp_" + "a".repeat(40),
                new KnowledgeEntryOrigin(TaskExecutionId.generate().value(), 1),
                actor.id(),
                NOW));

        KnowledgeDisclosureViolationException denied = assertThrows(
                KnowledgeDisclosureViolationException.class,
                () -> service.publish(context("publish-secret"), teamId, distilled.id(), 0));

        assertEquals("knowledge_disclosure_denied", denied.error().code().value());
        assertEquals("github_token", denied.error().details().get("patternFamily"));
        assertEquals(KnowledgeEntryStatus.DRAFT,
                repository.findById(organizationId, teamId, distilled.id())
                        .orElseThrow().status());
        assertTrue(store.events.isEmpty(), "no fact may escape a denied publication");
    }

    @Test
    void publishAllowsDistilledContentOnceTheHumanCuratesTheSecretAway() {
        KnowledgeEntry distilled = repository.create(KnowledgeEntry.createDistilled(
                new TeamScope(organizationId, teamId),
                ENTRY_KEY,
                KnowledgeCategory.RUNBOOK,
                "Deploy lessons",
                "Token was ghp_" + "a".repeat(40),
                new KnowledgeEntryOrigin(TaskExecutionId.generate().value(), 1),
                actor.id(),
                NOW));
        service.updateDraft(
                context("publish-curate"), teamId, distilled.id(), 0,
                new UpdateKnowledgeDraftCommand(
                        "Deploy lessons", "Rotate the token through the vault.", Optional.empty()));

        CommandExecution<KnowledgeEntry> execution =
                service.publish(context("publish-curated"), teamId, distilled.id(), 1);

        assertEquals(KnowledgeEntryStatus.PUBLISHED, execution.result().orElseThrow().status());
        assertEquals(1, store.events.size());
        assertEquals("KNOWLEDGE_VERSION_PUBLISHED", store.events.get(0).eventType().value());
    }

    @Test
    void publishDoesNotScanManuallyAuthoredEntries() {
        CommandExecution<KnowledgeEntry> created = service.create(
                context("publish-manual"),
                teamId,
                new CreateKnowledgeEntryCommand(
                        ENTRY_KEY, KnowledgeCategory.OTHER, "Snippet",
                        "Legacy note: ghp_" + "b".repeat(40)));

        CommandExecution<KnowledgeEntry> execution =
                service.publish(context("publish-manual-go"), teamId,
                        created.result().orElseThrow().id(), 0);

        assertEquals(KnowledgeEntryStatus.PUBLISHED, execution.result().orElseThrow().status());
    }

    @Test
    void retireKeepsTheLastEffectiveRevisionForAttribution() {
        KnowledgeEntry created = createEntry("retire-1", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();
        service.publish(context("retire-1-pub"), teamId, created.id(), 0);

        CommandExecution<KnowledgeEntry> execution = service.retire(
                context("retire-1-go"), teamId, created.id(), 1);

        KnowledgeEntry committed = execution.result().orElseThrow();
        assertEquals(KnowledgeEntryStatus.RETIRED, committed.status());
        assertEquals(2, committed.version());
        assertEquals(Optional.of(new KnowledgeEntryRevision(1)), committed.lastEffectiveRevision());

        assertEquals(3, store.events.size());
        DomainEventEnvelope<? extends DomainEvent> envelope = store.events.get(2);
        assertEquals("KNOWLEDGE_VERSION_RETIRED", envelope.eventType().value());
        KnowledgeVersionRetired payload = assertInstanceOf(KnowledgeVersionRetired.class, envelope.payload());
        assertEquals(1, payload.retiredRevision());
        assertEquals(
                repository.findVersion(organizationId, teamId, created.id(), new KnowledgeEntryRevision(1))
                        .orElseThrow().contentHash().value(),
                payload.contentHash());
        assertEquals(2, envelope.aggregateVersion());
    }

    @Test
    void deleteOfANeverPublishedEntryCarriesAZeroLastEffectiveRevision() {
        KnowledgeEntry created = createEntry("delete-1", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();

        CommandExecution<KnowledgeEntry> execution = service.delete(
                context("delete-1-go"), teamId, created.id(), 0);

        assertEquals(KnowledgeEntryStatus.DELETED, execution.result().orElseThrow().status());
        assertEquals(2, store.events.size());
        KnowledgeEntryDeleted payload =
                assertInstanceOf(KnowledgeEntryDeleted.class, store.events.get(1).payload());
        assertEquals(0, payload.lastEffectiveRevision());
    }

    @Test
    void deleteAfterPublishCarriesTheLastEffectiveRevision() {
        KnowledgeEntry created = createEntry("delete-2", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();
        service.publish(context("delete-2-pub"), teamId, created.id(), 0);

        CommandExecution<KnowledgeEntry> execution = service.delete(
                context("delete-2-go"), teamId, created.id(), 1);

        assertEquals(KnowledgeEntryStatus.DELETED, execution.result().orElseThrow().status());
        assertEquals(3, store.events.size());
        KnowledgeEntryDeleted payload =
                assertInstanceOf(KnowledgeEntryDeleted.class, store.events.get(2).payload());
        assertEquals(1, payload.lastEffectiveRevision());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void completedCommandReplaysItsOriginalReceiptWithoutNewSideEffects() {
        TeamCommandContext context = context("replay-1");
        CommandExecution<KnowledgeEntry> first = createEntry(context, KnowledgeCategory.RUNBOOK);
        int eventCount = store.events.size();

        CommandExecution<KnowledgeEntry> second = createEntry(context, KnowledgeCategory.RUNBOOK);

        assertTrue(second.replayed());
        assertEquals(first.receipt(), second.receipt());
        assertEquals(eventCount, store.events.size());
        assertEquals(1, repository.findByTeam(
                organizationId, teamId, KnowledgeEntryFilter.all(),
                new KnowledgeEntryPageRequest(Optional.empty(), 10)).items().size());
    }

    @Test
    void sameKeyWithDifferentSemanticsIsRejectedAsAConflict() {
        TeamCommandContext context = context("conflict-1");
        service.create(context, teamId, command(KnowledgeCategory.RUNBOOK, "On-call runbook"));

        IdempotencyConflictException conflict = assertThrows(
                IdempotencyConflictException.class,
                () -> service.create(context, teamId, command(KnowledgeCategory.GUIDE, "A different intent")));
        assertEquals("conflict-1", conflict.error().details().get("idempotencyKey"));
    }

    @Test
    void duplicateEntryKeyUnderADifferentCommandStillConflicts() {
        createEntry("dup-1", KnowledgeCategory.RUNBOOK);

        assertThrows(
                KnowledgeEntryKeyConflictException.class,
                () -> createEntry("dup-2", KnowledgeCategory.RUNBOOK));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void staleExpectedVersionIsRejectedBeforeAnySideEffect() {
        KnowledgeEntry created = createEntry("stale-1", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();

        assertThrows(
                OptimisticLockConflictException.class,
                () -> service.publish(context("stale-1"), teamId, created.id(), 7));

        assertEquals(1, store.events.size());
        assertEquals(KnowledgeEntryStatus.DRAFT, repository
                .findById(organizationId, teamId, created.id()).orElseThrow().status());
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void commandsRequireKnowledgeManageWhileReadsStayMemberWide() {
        store.revokeGrants();
        TeamCommandContext context = context("denied-1");

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> createEntry(context, KnowledgeCategory.RUNBOOK));
        assertEquals("manage this Team's knowledge", denied.error().details().get("action"));

        // Membership alone still reaches every read surface (D11).
        assertEquals(0, service.teamListing(
                access(), organizationId, teamId, KnowledgeEntryFilter.all(),
                new KnowledgeEntryPageRequest(Optional.empty(), 10)).items().size());
    }

    @Test
    void nonMembersAreDeniedEvenForReads() {
        store.evictMembers();

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> service.teamListing(
                        access(), organizationId, teamId, KnowledgeEntryFilter.all(),
                        new KnowledgeEntryPageRequest(Optional.empty(), 10)));
        assertEquals("access this Team's knowledge", denied.error().details().get("action"));
    }

    // ------------------------------------------------------------------ reads

    @Test
    void effectiveVersionOfAnUnpublishedEntryIsUnresolved() {
        KnowledgeEntry created = createEntry("effective-1", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();

        AggregateNotFoundException missing = assertThrows(
                AggregateNotFoundException.class,
                () -> service.effectiveVersion(access(), organizationId, teamId, created.id()));
        assertEquals("KnowledgeEntryEffectiveVersion", missing.error().details().get("aggregateType"));
    }

    @Test
    void missingEntryAndMissingVersionAreAggregateNotFound() {
        assertThrows(
                AggregateNotFoundException.class,
                () -> service.entry(access(), organizationId, teamId, KnowledgeEntryId.generate()));

        KnowledgeEntry created = createEntry("missing-1", KnowledgeCategory.RUNBOOK)
                .result().orElseThrow();
        assertThrows(
                AggregateNotFoundException.class,
                () -> service.version(
                        access(), organizationId, teamId, created.id(), new KnowledgeEntryRevision(9)));
    }

    // ------------------------------------------------------------------ helpers

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private TeamCommandContext context(String idempotencyKey) {
        return new TeamCommandContext(
                access(),
                IdempotencyKey.from(idempotencyKey),
                UUID.randomUUID(),
                Optional.empty());
    }

    private CreateKnowledgeEntryCommand command(KnowledgeCategory category, String title) {
        return new CreateKnowledgeEntryCommand(ENTRY_KEY, category, title, "Step one");
    }

    private CommandExecution<KnowledgeEntry> createEntry(String idempotencyKey, KnowledgeCategory category) {
        return createEntry(context(idempotencyKey), category);
    }

    private CommandExecution<KnowledgeEntry> createEntry(
            TeamCommandContext context, KnowledgeCategory category) {
        return service.create(context, teamId, command(category, "On-call runbook"));
    }

    private DomainEventEnvelope<? extends DomainEvent> singleEvent() {
        assertEquals(1, store.events.size());
        return store.events.get(0);
    }

    /** In-memory ports for every collaborator except the knowledge repository itself. */
    private static final class Store
            implements TeamRepository, TeamMembershipQuery, MemberRoleRepository,
                    DomainEventStore, OutboxRepository, CommandReceiptStore {

        private final TeamInitialization initialization;
        private final Map<String, ReceiptEntry> receipts = new HashMap<>();
        final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
        final List<PendingOutboxEvent> outbox = new ArrayList<>();
        final Map<String, CommandResult> results = new HashMap<>();
        List<TeamMember> members;
        List<TeamRole> roles;
        List<MemberRole> grants;

        private Store(TeamInitialization initialization, Principal actor) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
            TeamRole ownerRole = TeamRole.createBuiltIn(
                    TeamRoleId.generate(), initialization.team().scope(),
                    BuiltInTeamRole.TEAM_OWNER, NOW);
            this.roles = List.of(ownerRole);
            this.grants = List.of(MemberRole.grantOwner(
                    MemberRoleId.generate(), initialization.team(),
                    initialization.ownerMember(), ownerRole, actor.id(), NOW));
        }

        TeamRoleRepository rolesView() {
            return new TeamRoleRepository() {
                @Override
                public List<TeamRole> createAll(List<TeamRole> values) {
                    roles = List.copyOf(values);
                    return roles;
                }

                @Override
                public List<TeamRole> findByTeam(OrganizationId organization, TeamId team) {
                    return roles;
                }
            };
        }

        void revokeGrants() {
            grants = List.of();
        }

        void evictMembers() {
            members = List.of();
        }

        @Override
        public Team create(Team team) {
            return team;
        }

        @Override
        public Optional<Team> findById(OrganizationId organizationId, TeamId id) {
            return Optional.of(initialization.team())
                    .filter(team -> team.organizationId().equals(organizationId)
                            && team.id().equals(id));
        }

        @Override
        public Optional<UninitializedTeam> findUninitializedById(OrganizationId organizationId, TeamId id) {
            return Optional.empty();
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId organization, TeamId team) {
            return members;
        }

        @Override
        public MemberRole create(MemberRole memberRole) {
            grants = new ArrayList<>(grants);
            grants.add(memberRole);
            return memberRole;
        }

        @Override
        public List<MemberRole> findByMember(OrganizationId organizationId, TeamMemberId memberId) {
            return grants;
        }

        @Override
        public void append(DomainEventEnvelope<? extends DomainEvent> event) {
            events.add(event);
        }

        @Override
        public void enqueue(PendingOutboxEvent event) {
            outbox.add(event);
        }

        @Override
        public void saveResult(CommandResult result) {
            results.put(result.organizationId() + ":" + result.idempotencyKey(), result);
        }

        @Override
        public Optional<CommandResult> findResult(
                OrganizationId organizationId, IdempotencyKey key, PrincipalId actorId) {
            return Optional.ofNullable(results.get(organizationId + ":" + key))
                    .filter(result -> result.actorId().equals(actorId));
        }

        @Override
        public Optional<CommandReceipt> findCompleted(
                OrganizationId organizationId,
                IdempotencyKey idempotencyKey,
                String commandType,
                CommandRequestHash requestHash) {
            ReceiptEntry existing = receipts.get(organizationId + ":" + idempotencyKey);
            if (existing == null || existing.receipt == null
                    || !existing.request.commandType().equals(commandType)
                    || !existing.request.requestHash().equals(requestHash)) {
                return Optional.empty();
            }
            return Optional.of(existing.receipt);
        }

        @Override
        public CommandReservation reserve(CommandReservationRequest request) {
            String key = request.organizationId() + ":" + request.idempotencyKey();
            ReceiptEntry existing = receipts.get(key);
            if (existing == null) {
                receipts.put(key, new ReceiptEntry(request, null));
                return CommandReservation.newlyAcquired();
            }
            if (!existing.request.commandType().equals(request.commandType())
                    || !existing.request.requestHash().equals(request.requestHash())) {
                throw new IdempotencyConflictException(
                        request.idempotencyKey().value(),
                        existing.request.requestHash().value(),
                        request.requestHash().value());
            }
            return CommandReservation.replay(existing.receipt);
        }

        @Override
        public void complete(
                OrganizationId organizationId,
                IdempotencyKey idempotencyKey,
                CommandReceipt receipt,
                UtcTimestamp completedAt) {
            String key = organizationId + ":" + idempotencyKey;
            ReceiptEntry existing = receipts.get(key);
            receipts.put(key, new ReceiptEntry(existing.request, receipt));
        }

        private record ReceiptEntry(CommandReservationRequest request, CommandReceipt receipt) {}
    }

    private static final class DirectTransactionExecutor implements TransactionExecutor {
        @Override
        public <T> T required(Supplier<T> operation) {
            return operation.get();
        }
    }
}
