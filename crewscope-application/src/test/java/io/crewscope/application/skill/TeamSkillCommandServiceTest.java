package io.crewscope.application.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.crewscope.domain.shared.DomainEvent;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.IdempotencyConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.event.DomainEventEnvelope;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.skill.TeamSkill;
import io.crewscope.domain.skill.TeamSkillDisabledException;
import io.crewscope.domain.skill.TeamSkillDisclosureViolationException;
import io.crewscope.domain.skill.TeamSkillId;
import io.crewscope.domain.skill.TeamSkillKey;
import io.crewscope.domain.skill.TeamSkillKeyConflictException;
import io.crewscope.domain.skill.TeamSkillRevision;
import io.crewscope.domain.skill.TeamSkillStatus;
import io.crewscope.domain.skill.TeamSkillVersionUnchangedException;
import io.crewscope.domain.skill.event.TeamSkillCreated;
import io.crewscope.domain.skill.event.TeamSkillDisabled;
import io.crewscope.domain.skill.event.TeamSkillVersionPublished;
import io.crewscope.domain.team.BuiltInTeamRole;
import io.crewscope.domain.team.MemberRole;
import io.crewscope.domain.team.MemberRoleId;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamRole;
import io.crewscope.domain.team.TeamRoleId;
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
 * Command-face contract for A03a: the SKILL_MANAGE gate, the write-side switch,
 * unconditional disclosure scanning, idempotent receipts, the head-version ETag source
 * and the exact three-event lifecycle, including the silent draft replacement.
 */
final class TeamSkillCommandServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T09:00:00Z");
    private static final TeamSkillKey SKILL_KEY = TeamSkillKey.parse("deploy-runbook-v2");

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
    private final InMemoryTeamSkillRepository repository = new InMemoryTeamSkillRepository();
    private final Store store = new Store(initialization, actor);

    private TeamSkillCommandService enabledService() {
        return new TeamSkillCommandService(
                repository,
                store,
                store,
                store.rolesView(),
                store,
                store,
                store,
                store,
                new DirectTransactionExecutor(),
                () -> NOW,
                true);
    }

    private TeamSkillCommandService switchedOffService() {
        return new TeamSkillCommandService(
                repository,
                store,
                store,
                store.rolesView(),
                store,
                store,
                store,
                store,
                new DirectTransactionExecutor(),
                () -> NOW,
                false);
    }

    private static String document(String description, String body) {
        return """
                ---
                name: deploy-runbook-v2
                description: %s
                ---

                %s
                """
                .formatted(description, body);
    }

    // ------------------------------------------------------------------ lifecycle facts

    @Test
    void createEmitsTheCreatedFactAndSavesTheResult() {
        CommandExecution<TeamSkill> execution = createSkill("create-1", document(
                "Standard rollback drill.", "Drain the pool."));

        TeamSkill skill = execution.result().orElseThrow();
        assertEquals(TeamSkillStatus.DRAFT, skill.status());
        assertEquals(0, skill.version());
        assertTrue(skill.origin().isEmpty(), "A03a authors skills without an origin");

        DomainEventEnvelope<? extends DomainEvent> envelope = singleEvent();
        assertEquals("TEAM_SKILL_CREATED", envelope.eventType().value());
        TeamSkillCreated payload = assertInstanceOf(TeamSkillCreated.class, envelope.payload());
        assertEquals(skill.id().value(), payload.skillId());
        assertEquals("deploy-runbook-v2", payload.skillKey());
        assertEquals("Standard rollback drill.", payload.description());
        assertEquals("TEAM_SKILL", envelope.aggregate().type());
        assertEquals(skill.id().value(), envelope.aggregate().id());
        assertEquals(0, envelope.aggregateVersion());
        assertEquals(Optional.of(teamId), envelope.teamId());
        assertEquals(Optional.of("create-1"), envelope.idempotencyKey());
        assertEquals(1, store.outbox.size());
        assertEquals(envelope.eventId(), execution.receipt().domainEventId());
        assertEquals(0, execution.receipt().committedVersion());

        CommandResult result = store.findResult(organizationId, IdempotencyKey.from("create-1"), actor.id())
                .orElseThrow();
        assertEquals(CommandResult.ResourceType.TEAM_SKILL, result.resourceType());
        assertEquals(skill.id().value(), result.resourceId());
        assertEquals(Optional.empty(), result.projectId());
        assertEquals(0, result.resourceVersion());
    }

    @Test
    void updateDraftEmitsNoEventAndLocatesTheReceiptByTheSkillId() {
        TeamSkill created = createSkill("draft-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();

        CommandExecution<TeamSkill> execution = enabledService().updateDraft(
                context("draft-1-edit"),
                teamId,
                created.id(),
                0,
                new UpdateTeamSkillDraftCommand(document(
                        "Revised drill.", "Drain the pool, then verify the tip.")));

        TeamSkill committed = execution.result().orElseThrow();
        assertEquals(1, committed.version());
        assertEquals(TeamSkillStatus.DRAFT, committed.status());
        assertEquals(0, committed.latestRevision());
        assertEquals(1, store.events.size());
        assertEquals(1, store.outbox.size());
        assertEquals(created.id().value(), execution.receipt().domainEventId());
        assertEquals(1, execution.receipt().committedVersion());
        assertTrue(
                store.findResult(organizationId, IdempotencyKey.from("draft-1-edit"), actor.id()).isEmpty(),
                "updateDraft must not save a command result");
    }

    @Test
    void publishAppendsAnImmutableVersionAndEmitsThePublishedFact() {
        TeamSkill created = createSkill("publish-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();

        CommandExecution<TeamSkill> execution = enabledService().publish(
                context("publish-1-go"), teamId, created.id(), 0);

        TeamSkill committed = execution.result().orElseThrow();
        assertEquals(TeamSkillStatus.PUBLISHED, committed.status());
        assertEquals(1, committed.version());
        assertEquals(1, committed.effectiveRevision().orElseThrow().value());
        assertEquals(1, committed.latestRevision());

        assertEquals(2, store.events.size());
        DomainEventEnvelope<? extends DomainEvent> envelope = store.events.get(1);
        assertEquals("TEAM_SKILL_VERSION_PUBLISHED", envelope.eventType().value());
        TeamSkillVersionPublished payload =
                assertInstanceOf(TeamSkillVersionPublished.class, envelope.payload());
        assertEquals(1, payload.revision());
        assertNull(payload.rolledBackFromRevision());
        assertEquals(
                repository.findVersion(organizationId, teamId, created.id(), new TeamSkillRevision(1))
                        .orElseThrow().contentHash().value(),
                payload.contentHash());
        assertEquals(committed.version(), envelope.aggregateVersion());
        assertEquals(
                new TeamSkillRevision(1),
                enabledService().effectiveVersion(access(), organizationId, teamId, created.id())
                        .revision());
    }

    @Test
    void publishScansEveryDocumentUnconditionallyEvenManualOnes() {
        // The A03a gate is stricter than A02's origin-only scan: a Skill is an
        // instruction surface for every later execution of the Team, so a manually
        // authored document with a credential-looking token is refused too.
        TeamSkill created = createSkill("publish-secret", document(
                "Lessons.", "Token was ghp_" + "a".repeat(40)))
                .result().orElseThrow();

        TeamSkillDisclosureViolationException denied = assertThrows(
                TeamSkillDisclosureViolationException.class,
                () -> enabledService().publish(context("publish-secret-go"), teamId, created.id(), 0));

        assertEquals("skill_disclosure_denied", denied.error().code().value());
        assertEquals("github_token", denied.error().details().get("patternFamily"));
        assertEquals(TeamSkillStatus.DRAFT,
                repository.findById(organizationId, teamId, created.id())
                        .orElseThrow().status());
        assertEquals(1, store.events.size(), "no fact may escape a denied publication");
    }

    @Test
    void publishRefusesADocumentIdenticalToTheEffectiveVersion() {
        TeamSkill created = createSkill("unchanged-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();
        TeamSkillCommandService service = enabledService();
        service.publish(context("unchanged-1-pub"), teamId, created.id(), 0);
        // Publishing the same document again — even through an explicit re-draft.
        TeamSkill redrafted = service.updateDraft(
                context("unchanged-1-redraft"), teamId, created.id(), 1,
                new UpdateTeamSkillDraftCommand(document(
                        "Standard rollback drill.", "Drain the pool.")))
                .result().orElseThrow();

        TeamSkillVersionUnchangedException unchanged = assertThrows(
                TeamSkillVersionUnchangedException.class,
                () -> service.publish(context("unchanged-1-go"), teamId, redrafted.id(), 2));

        assertEquals("skill_version_unchanged", unchanged.error().code().value());
        assertEquals(2, store.events.size());
    }

    @Test
    void disableKeepsTheLastEffectiveRevisionAndEmitsTheReason() {
        TeamSkill created = createSkill("disable-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();
        enabledService().publish(context("disable-1-pub"), teamId, created.id(), 0);

        CommandExecution<TeamSkill> execution = enabledService().disable(
                context("disable-1-go"), teamId, created.id(), 1, "  Superseded.  ");

        TeamSkill committed = execution.result().orElseThrow();
        assertEquals(TeamSkillStatus.DISABLED, committed.status());
        assertEquals(2, committed.version());
        assertEquals(Optional.of(new TeamSkillRevision(1)), committed.lastEffectiveRevision());
        assertEquals("Superseded.", committed.disableReason().orElseThrow());

        assertEquals(3, store.events.size());
        DomainEventEnvelope<? extends DomainEvent> envelope = store.events.get(2);
        assertEquals("TEAM_SKILL_DISABLED", envelope.eventType().value());
        TeamSkillDisabled payload = assertInstanceOf(TeamSkillDisabled.class, envelope.payload());
        assertEquals("Superseded.", payload.reason());
        assertEquals(2, envelope.aggregateVersion());

        // The loading gate closes: a disabled head no longer resolves.
        assertThrows(
                AggregateNotFoundException.class,
                () -> enabledService().effectiveVersion(access(), organizationId, teamId, created.id()));
    }

    @Test
    void rollbackActivatesHistoricalContentAsANewRevision() {
        TeamSkillCommandService service = enabledService();
        TeamSkill created = createSkill("rollback-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();
        service.publish(context("rollback-1-pub1"), teamId, created.id(), 0);
        service.updateDraft(context("rollback-1-draft"), teamId, created.id(), 1,
                new UpdateTeamSkillDraftCommand(document(
                        "Drifted drill.", "Skip the verification.")));
        service.publish(context("rollback-1-pub2"), teamId, created.id(), 2);

        CommandExecution<TeamSkill> execution =
                service.rollback(context("rollback-1-go"), teamId, created.id(), 3, 1);

        TeamSkill committed = execution.result().orElseThrow();
        assertEquals(TeamSkillStatus.PUBLISHED, committed.status());
        assertEquals(Optional.of(new TeamSkillRevision(3)), committed.effectiveRevision());
        assertEquals(3, committed.latestRevision());

        assertEquals(4, store.events.size());
        TeamSkillVersionPublished payload =
                assertInstanceOf(TeamSkillVersionPublished.class, store.events.get(3).payload());
        assertEquals(3, payload.revision());
        assertEquals(2L, payload.rolledBackFromRevision());
        // The appended revision carries the historical document verbatim.
        assertEquals(
                repository.findVersion(organizationId, teamId, created.id(), new TeamSkillRevision(1))
                        .orElseThrow().contentHash().value(),
                payload.contentHash());
    }

    @Test
    void rollbackOntoTheCurrentlyEffectiveRevisionIsRefused() {
        TeamSkillCommandService service = enabledService();
        TeamSkill created = createSkill("rollback-2", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();
        service.publish(context("rollback-2-pub"), teamId, created.id(), 0);

        TeamSkillVersionUnchangedException unchanged = assertThrows(
                TeamSkillVersionUnchangedException.class,
                () -> service.rollback(context("rollback-2-go"), teamId, created.id(), 1, 1));

        assertEquals("skill_version_unchanged", unchanged.error().code().value());
        assertEquals(2, store.events.size());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    void completedCommandReplaysItsOriginalReceiptWithoutNewSideEffects() {
        TeamCommandContext context = context("replay-1");
        CommandExecution<TeamSkill> first = createSkill(context, document(
                "Standard rollback drill.", "Drain the pool."));
        int eventCount = store.events.size();

        CommandExecution<TeamSkill> second = createSkill(context, document(
                "Standard rollback drill.", "Drain the pool."));

        assertTrue(second.replayed());
        assertEquals(first.receipt(), second.receipt());
        assertEquals(eventCount, store.events.size());
        assertEquals(1, repository.findByTeam(
                organizationId, teamId, TeamSkillFilter.all(),
                new TeamSkillPageRequest(Optional.empty(), 10)).items().size());
    }

    @Test
    void sameKeyWithDifferentSemanticsIsRejectedAsAConflict() {
        TeamCommandContext context = context("conflict-1");
        enabledService().create(context, teamId, command(document(
                "Standard rollback drill.", "Drain the pool.")));

        IdempotencyConflictException conflict = assertThrows(
                IdempotencyConflictException.class,
                () -> enabledService().create(context, teamId, command(document(
                        "A different intent.", "Do nothing."))));
        assertEquals("conflict-1", conflict.error().details().get("idempotencyKey"));
    }

    @Test
    void sameKeyRollbackWithADifferentTargetIsRejectedAsAConflict() {
        TeamSkillCommandService service = enabledService();
        TeamSkill created = createSkill("rb-conflict", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();
        service.publish(context("rb-conflict-pub1"), teamId, created.id(), 0);
        service.updateDraft(context("rb-conflict-draft"), teamId, created.id(), 1,
                new UpdateTeamSkillDraftCommand(document(
                        "Drifted drill.", "Skip the verification.")));
        service.publish(context("rb-conflict-pub2"), teamId, created.id(), 2);

        TeamCommandContext context = context("rb-conflict-go");
        service.rollback(context, teamId, created.id(), 3, 1);

        IdempotencyConflictException conflict = assertThrows(
                IdempotencyConflictException.class,
                () -> service.rollback(context, teamId, created.id(), 4, 2));
        assertEquals("rb-conflict-go", conflict.error().details().get("idempotencyKey"));
    }

    @Test
    void duplicateSkillKeyUnderADifferentCommandStillConflicts() {
        createSkill("dup-1", document("Standard rollback drill.", "Drain the pool."));

        assertThrows(
                TeamSkillKeyConflictException.class,
                () -> createSkill("dup-2", document("Standard rollback drill.", "Drain again.")));
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void staleExpectedVersionIsRejectedBeforeAnySideEffect() {
        TeamSkill created = createSkill("stale-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();

        assertThrows(
                OptimisticLockConflictException.class,
                () -> enabledService().publish(context("stale-1"), teamId, created.id(), 7));

        assertEquals(1, store.events.size());
        assertEquals(TeamSkillStatus.DRAFT, repository
                .findById(organizationId, teamId, created.id()).orElseThrow().status());
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void commandsRequireSkillManageWhileReadsStayMemberWide() {
        store.revokeGrants();
        TeamCommandContext context = context("denied-1");

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> createSkill(context, document(
                        "Standard rollback drill.", "Drain the pool.")));
        assertEquals("manage this Team's skills", denied.error().details().get("action"));

        // Membership alone still reaches every read surface.
        assertEquals(0, enabledService().teamListing(
                access(), organizationId, teamId, TeamSkillFilter.all(),
                new TeamSkillPageRequest(Optional.empty(), 10)).items().size());
    }

    @Test
    void nonMembersAreDeniedEvenForReads() {
        store.evictMembers();

        PolicyDeniedException denied = assertThrows(
                PolicyDeniedException.class,
                () -> enabledService().teamListing(
                        access(), organizationId, teamId, TeamSkillFilter.all(),
                        new TeamSkillPageRequest(Optional.empty(), 10)));
        assertEquals("access this Team's skills", denied.error().details().get("action"));
    }

    // ------------------------------------------------------------------ write switch

    @Test
    void switchedOffRefusesEveryWriteButKeepsEveryRead() {
        TeamSkill created = createSkill("switch-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();
        TeamSkillCommandService off = switchedOffService();

        assertThrows(TeamSkillDisabledException.class, () -> off.create(
                context("switch-2"), teamId, command(document(
                        "Another drill.", "Do nothing."))));
        assertThrows(TeamSkillDisabledException.class, () -> off.updateDraft(
                context("switch-3"), teamId, created.id(), 0,
                new UpdateTeamSkillDraftCommand(document(
                        "Standard rollback drill.", "Edited."))));
        assertThrows(TeamSkillDisabledException.class, () -> off.publish(
                context("switch-4"), teamId, created.id(), 0));
        assertThrows(TeamSkillDisabledException.class, () -> off.disable(
                context("switch-5"), teamId, created.id(), 0, "Retired."));
        assertThrows(TeamSkillDisabledException.class, () -> off.rollback(
                context("switch-6"), teamId, created.id(), 0, 1));

        // The switch never closes the read side: historical evidence stays viewable.
        assertEquals(TeamSkillStatus.DRAFT, off.skill(
                access(), organizationId, teamId, created.id()).status());
        assertEquals(1, off.teamListing(
                access(), organizationId, teamId, TeamSkillFilter.all(),
                new TeamSkillPageRequest(Optional.empty(), 10)).items().size());
        assertEquals("skill_disabled",
                assertThrows(TeamSkillDisabledException.class, () -> off.publish(
                                context("switch-7"), teamId, created.id(), 0))
                        .error().code().value());
    }

    // ------------------------------------------------------------------ validation

    @Test
    void theReservedBuiltInNameIsRefusedAtCommandConstruction() {
        DomainValidationException reserved = assertThrows(
                DomainValidationException.class,
                () -> new CreateTeamSkillCommand(
                        TeamSkillKey.parse("java-spring-v1"), document("x", "Body.")));

        assertEquals("teamSkill.skillKey", reserved.error().details().get("field"));
    }

    @Test
    void aDriftingFrontmatterNameIsRefused() {
        DomainValidationException drift = assertThrows(
                DomainValidationException.class,
                () -> enabledService().create(
                        context("drift-1"), teamId, new CreateTeamSkillCommand(
                                SKILL_KEY,
                                document("Standard rollback drill.", "Body.").replace(
                                        "name: deploy-runbook-v2", "name: other-key"))));

        assertEquals("teamSkill.draft", drift.error().details().get("field"));
    }

    @Test
    void missingSkillAndMissingVersionAreAggregateNotFound() {
        assertThrows(
                AggregateNotFoundException.class,
                () -> enabledService().skill(access(), organizationId, teamId, TeamSkillId.generate()));

        TeamSkill created = createSkill("missing-1", document(
                "Standard rollback drill.", "Drain the pool."))
                .result().orElseThrow();
        assertThrows(
                AggregateNotFoundException.class,
                () -> enabledService().version(
                        access(), organizationId, teamId, created.id(), new TeamSkillRevision(9)));
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

    private CreateTeamSkillCommand command(String content) {
        return new CreateTeamSkillCommand(SKILL_KEY, content);
    }

    private CommandExecution<TeamSkill> createSkill(String idempotencyKey, String content) {
        return createSkill(context(idempotencyKey), content);
    }

    private CommandExecution<TeamSkill> createSkill(TeamCommandContext context, String content) {
        return enabledService().create(context, teamId, command(content));
    }

    private DomainEventEnvelope<? extends DomainEvent> singleEvent() {
        assertEquals(1, store.events.size());
        return store.events.get(0);
    }

    /** In-memory ports for every collaborator except the skill repository itself. */
    private static final class Store
            implements TeamRepository, TeamMembershipQuery, MemberRoleRepository,
                    DomainEventStore, OutboxRepository, CommandReceiptStore {

        private final TeamInitialization initialization;
        private final Map<String, ReceiptEntry> receipts = new HashMap<>();
        final List<DomainEventEnvelope<? extends DomainEvent>> events = new ArrayList<>();
        final List<PendingOutboxEvent> outbox = new ArrayList<>();
        private final Map<String, CommandResult> results = new HashMap<>();
        private List<TeamMember> members;
        private List<TeamRole> roles;
        private List<MemberRole> grants;

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
