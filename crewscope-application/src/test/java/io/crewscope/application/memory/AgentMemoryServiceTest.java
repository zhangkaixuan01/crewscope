package io.crewscope.application.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentMemoryEntry;
import io.crewscope.domain.agent.AgentMemoryKey;
import io.crewscope.domain.agent.AgentMemoryOwner;
import io.crewscope.domain.agent.AgentMemoryOwnerKey;
import io.crewscope.domain.agent.AgentMemoryPolicy;
import io.crewscope.domain.agent.AgentMemoryPolicyReference;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.AggregateNotFoundException;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.Team;
import io.crewscope.domain.team.TeamInitialization;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamStatus;
import io.crewscope.domain.team.UninitializedTeam;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Service contract of the agent assistant memory lifecycle (M10-I02a, S01 §3.6): the
 * member-level self-only guards, the three view states, the runtime write port's explicit
 * outcomes (switch, configuration, policy, capacity, stale clearance), the structurally
 * idempotent clear, and the policy-version space isolation. The repository fake replays the
 * generation/TTL/space visibility contract the JDBC adapter owns.
 */
@Execution(ExecutionMode.SAME_THREAD)
final class AgentMemoryServiceTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-04T08:00:00Z");
    private static final AgentMemoryPolicyReference DEFAULT_REF =
            AgentMemoryPolicy.defaults().reference();

    private final OrganizationId organizationId = OrganizationId.generate();
    private final Principal actor = Principal.create(
            PrincipalId.generate(),
            PrincipalScope.organization(organizationId),
            PrincipalType.USER,
            Optional.empty(),
            "Owner",
            Optional.empty(),
            PrincipalVisibility.ORGANIZATION,
            NOW);
    private final TeamInitialization initialization =
            TeamInitialization.create(actor, "Platform", NOW);
    private final TeamId teamId = initialization.team().id();
    private final AgentProfileId profileId = AgentProfileId.generate();

    private final Store store = new Store(initialization);
    private final FakeAgentMemoryRepository memory = new FakeAgentMemoryRepository();
    private final AgentProfileRepository profiles = mock(AgentProfileRepository.class);
    private final AgentConfigurationRepository configurations =
            mock(AgentConfigurationRepository.class);

    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        lenient().when(profiles.findById(organizationId, profileId))
                .thenReturn(Optional.of(profile()));
        lenient().when(configurations.findCurrent(organizationId, profileId))
                .thenReturn(Optional.of(configuration(Optional.of(DEFAULT_REF))));
        service = service(true);
    }

    private AgentMemoryService service(boolean memoryEnabled) {
        return new AgentMemoryService(
                memory, profiles, configurations, new DefaultAgentMemoryPolicyCatalog(),
                store, store, directTransactions(), () -> NOW, memoryEnabled);
    }

    // ------------------------------------------------------------------ guards (self only)

    @Test
    void guardsDenyForeignOrganizationsStrangersAndNonMembers() {
        Principal stranger = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(OrganizationId.generate()),
                PrincipalType.USER,
                Optional.empty(),
                "Stranger",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        assertThrows(PolicyDeniedException.class, () -> service(true).view(
                new TeamAccessContext(stranger, true), organizationId, teamId, profileId));

        store.members = List.of();
        assertThrows(PolicyDeniedException.class, () -> service(true).view(
                access(), organizationId, teamId, profileId));
        assertThrows(PolicyDeniedException.class, () -> service(true).clear(
                access(), organizationId, teamId, profileId));
    }

    @Test
    void guardsRejectMissingTeamsUnreadyTeamsAndForeignProfiles() {
        TeamId missingTeam = TeamId.generate();
        assertThrows(AggregateNotFoundException.class, () -> service(true).view(
                access(), organizationId, missingTeam, profileId));

        store.uninitialized = true;
        DomainValidationException unready = assertThrows(
                DomainValidationException.class,
                () -> service(true).view(access(), organizationId, teamId, profileId));
        assertEquals("team.initializationStatus",
                unready.error().details().get("field"));
        store.uninitialized = false;

        when(profiles.findById(organizationId, profileId))
                .thenReturn(Optional.empty());
        assertThrows(AggregateNotFoundException.class, () -> service(true).view(
                access(), organizationId, teamId, profileId));

        // A profile of another team answers the same not-found shape, never a cross-team read.
        TeamInitialization otherTeam = TeamInitialization.create(actor, "Other", NOW);
        when(profiles.findById(organizationId, profileId))
                .thenReturn(Optional.of(profile(otherTeam)));
        assertThrows(AggregateNotFoundException.class, () -> service(true).view(
                access(), organizationId, teamId, profileId));
    }

    // ------------------------------------------------------------------ view states

    @Test
    void anUnconfiguredAgentAnswersAnEmptyViewWithoutTouchingStorage() {
        when(configurations.findCurrent(organizationId, profileId))
                .thenReturn(Optional.empty());

        AgentMemoryView view = service(true).view(access(), organizationId, teamId, profileId);

        assertTrue(view.policyReference().isEmpty());
        assertTrue(view.policy().isEmpty());
        assertTrue(view.entries().isEmpty());
        assertEquals(0L, view.clearanceGeneration());
        assertTrue(memory.entries.isEmpty(), "a read never creates rows");
    }

    @Test
    void viewListsVisibleEntriesOfTheConfiguredPolicySpace() {
        seedEntry("code-style", "tabs over spaces", 0);
        seedEntry("reply-language", "简体中文", 0);
        // Same key in another policy space and a stale-generation row stay invisible.
        seedEntry("reply-language", "Deutsch", 0, DEFAULT_REF.policyId(), 2);
        seedEntry("reply-language", "stale", 1);

        AgentMemoryView view = service(true).view(access(), organizationId, teamId, profileId);

        assertTrue(view.policyReference().isPresent());
        assertEquals(AgentMemoryPolicy.defaults(), view.policy().orElseThrow());
        assertEquals(0L, view.clearanceGeneration());
        assertEquals(List.of("code-style", "reply-language"),
                view.entries().stream().map(entry -> entry.memoryKey().value()).toList(),
                "memory_key ascending, current generation and space only");
    }

    @Test
    void anUnresolvablePolicyReferenceDegradesInsteadOfAnsweringEmpty() {
        when(configurations.findCurrent(organizationId, profileId))
                .thenReturn(Optional.of(configuration(Optional.of(
                        new AgentMemoryPolicyReference(UUID.randomUUID(), 1)))));

        AgentMemoryView view = service(true).view(access(), organizationId, teamId, profileId);

        assertTrue(view.policyReference().isPresent());
        assertTrue(view.policyUnavailable());
        assertTrue(view.entries().isEmpty());
    }

    // ------------------------------------------------------------------ write port outcomes

    @Test
    void upsertWritesSlidesTheTtlAndOverwritesWithoutOccupyingANewSlot() {
        AgentMemoryUpsertResult written =
                service(true).upsert(ownerKey(), new AgentMemoryKey("code-style"), "spaces");
        assertEquals(AgentMemoryUpsertResult.Outcome.WRITTEN, written.outcome());
        assertTrue(written.entry().isPresent());
        assertEquals(1, written.entryCount());
        assertEquals(100, written.maxEntries());
        assertEquals(
                NOW.value().plus(Duration.ofDays(90)),
                written.entry().orElseThrow().expiresAt().value(),
                "writing refreshes the sliding TTL");

        AgentMemoryUpsertResult overwritten =
                service(true).upsert(ownerKey(), new AgentMemoryKey("code-style"), "tabs");
        assertEquals(AgentMemoryUpsertResult.Outcome.WRITTEN, overwritten.outcome());
        assertEquals("tabs", overwritten.entry().orElseThrow().value());
        assertEquals(1, overwritten.entryCount(), "an overwrite does not occupy a new slot");
    }

    @Test
    void capacityExhaustionRejectsTheWriteExplicitlyInsteadOfEvicting() {
        for (int slot = 0; slot < 100; slot++) {
            seedEntry("key-" + String.format("%03d", slot), "value " + slot, 0);
        }

        AgentMemoryUpsertResult rejected = service(true).upsert(
                ownerKey(), new AgentMemoryKey("one-too-many"), "nope");

        assertEquals(AgentMemoryUpsertResult.Outcome.CAPACITY_EXCEEDED, rejected.outcome());
        assertTrue(rejected.entry().isEmpty());
        assertEquals(100, rejected.entryCount());
        assertEquals(100, rejected.maxEntries());
        assertEquals(100, memory.entries.size(), "nothing was evicted");

        AgentMemoryUpsertResult overwrite = service(true).upsert(
                ownerKey(), new AgentMemoryKey("key-000"), "refreshed");
        assertEquals(AgentMemoryUpsertResult.Outcome.WRITTEN, overwrite.outcome(),
                "an existing key still overwrites at full capacity");
    }

    @Test
    void upsertRefusesWhenDisabledUnconfiguredOrUnresolvable() {
        assertEquals(AgentMemoryUpsertResult.Outcome.MEMORY_DISABLED,
                service(false).upsert(ownerKey(), new AgentMemoryKey("code-style"), "x").outcome());

        when(configurations.findCurrent(organizationId, profileId))
                .thenReturn(Optional.of(configuration(Optional.empty())));
        assertEquals(AgentMemoryUpsertResult.Outcome.NOT_CONFIGURED,
                service(true).upsert(ownerKey(), new AgentMemoryKey("code-style"), "x").outcome());

        when(configurations.findCurrent(organizationId, profileId))
                .thenReturn(Optional.of(configuration(Optional.of(
                        new AgentMemoryPolicyReference(UUID.randomUUID(), 1)))));
        assertEquals(AgentMemoryUpsertResult.Outcome.POLICY_UNAVAILABLE,
                service(true).upsert(ownerKey(), new AgentMemoryKey("code-style"), "x").outcome());
    }

    @Test
    void theFrozenValueAndKeyBoundsHoldAtTheWritePort() {
        assertThrows(DomainValidationException.class, () ->
                service(true).upsert(ownerKey(), new AgentMemoryKey("Bad_Key"), "x"));
        assertThrows(DomainValidationException.class, () ->
                service(true).upsert(ownerKey(), new AgentMemoryKey("code-style"), "a".repeat(1025)));
    }

    // ------------------------------------------------------------------ clear and resurrection

    @Test
    void clearAdvancesTheGenerationAcrossSpacesAndIsStructurallyIdempotent() {
        seedEntry("reply-language", "中文", 0);
        seedEntry("code-style", "tabs", 0, DEFAULT_REF.policyId(), 2);

        AgentMemoryClearance cleared =
                service(true).clear(access(), organizationId, teamId, profileId);
        assertEquals(2, cleared.clearedCount(), "entries of every policy space go at once");
        assertEquals(1, cleared.clearanceGeneration());

        AgentMemoryClearance repeated =
                service(true).clear(access(), organizationId, teamId, profileId);
        assertEquals(0, repeated.clearedCount());
        assertEquals(2, repeated.clearanceGeneration(), "a repeated clear only advances");
    }

    @Test
    void aWriteRacingAClearIsRejectedAndNeverResurrectsClearedMemory() {
        seedEntry("code-style", "tabs", 0);
        service(true).clear(access(), organizationId, teamId, profileId);
        assertTrue(memory.entries.isEmpty());

        // The write observed generation 1; a concurrent clear bumps the owner to 2 under
        // the FOR UPDATE lock the adapter holds — the stale generation is rejected.
        memory.clearOnNextUpsert = true;
        AgentMemoryUpsertResult stale = service(true).upsert(
                ownerKey(), new AgentMemoryKey("code-style"), "revival attempt");

        assertEquals(AgentMemoryUpsertResult.Outcome.STALE_CLEARANCE, stale.outcome());
        assertTrue(memory.entries.isEmpty(), "the cleared rows stay gone");

        // The very next write under the current generation succeeds; the space is usable again.
        AgentMemoryUpsertResult fresh = service(true).upsert(
                ownerKey(), new AgentMemoryKey("code-style"), "fresh after clear");
        assertEquals(AgentMemoryUpsertResult.Outcome.WRITTEN, fresh.outcome());
    }

    // ------------------------------------------------------------------ touch and listing

    @Test
    void touchRenewsOnlyVisibleEntriesAndOnlyWhileEnabled() {
        assertFalse(service(false).touch(ownerKey(), new AgentMemoryKey("reply-language")));
        assertFalse(service(true).touch(ownerKey(), new AgentMemoryKey("missing")));

        seedEntry("reply-language", "中文", 0);
        assertTrue(service(true).touch(ownerKey(), new AgentMemoryKey("reply-language")));
        assertEquals(
                NOW.value().plus(Duration.ofDays(90)),
                memory.entries.get(0).expiresAt().value());

        // An expired entry is not "used": no renewal, invisible to reads.
        AgentMemoryEntry current = memory.entries.get(0);
        memory.entries.set(0, AgentMemoryEntry.reconstitute(
                current.owner(), current.policy(), current.memoryKey(), current.value(),
                current.clearanceGeneration(), current.version(),
                UtcTimestamp.from(NOW.value().minus(Duration.ofDays(1))), current.audit()));
        assertFalse(service(true).touch(ownerKey(), new AgentMemoryKey("reply-language")));
    }

    @Test
    void listServesTheInjectionReadOfTheConfiguredSpaceWithoutRenewal() {
        seedEntry("reply-language", "中文", 0);
        UtcTimestamp before = memory.entries.get(0).expiresAt();

        List<AgentMemoryEntry> listed = service(true).list(ownerKey());

        assertEquals(1, listed.size());
        assertEquals(before, memory.entries.get(0).expiresAt(), "listing never slides the TTL");
    }

    // ------------------------------------------------------------------ fixtures

    private TeamAccessContext access() {
        return new TeamAccessContext(actor, false);
    }

    private AgentMemoryOwnerKey ownerKey() {
        return new AgentMemoryOwnerKey(
                organizationId, teamId, profileId, actor.id());
    }

    private AgentTemplateDefinition template() {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(organizationId),
                new AgentTemplateKey("memory-assistant"),
                AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.USER),
                Set.of(AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("team.execute")), Set.of()),
                AgentTemplatePolicy.define(
                        "Approved Team execution baseline.",
                        Set.of(),
                        Set.of(),
                        Optional.empty(),
                        Set.of(
                                AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                                AgentConfigurableSlot.MODEL_BINDING,
                                AgentConfigurableSlot.KNOWLEDGE_SCOPE),
                        Set.of()),
                actor.id(),
                NOW);
    }

    private AgentProfile profile() {
        return profile(initialization);
    }

    /** The same profile shape bound to another team: guards must answer it as not-found. */
    private AgentProfile profile(TeamInitialization team) {
        Principal specialist = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, team.team().id()),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(actor.id()),
                "Team-scope specialist",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                NOW);
        return AgentProfile.createTemplateInstance(
                profileId,
                team.defaultWorkspace(),
                specialist,
                AgentOwnership.user(
                        organizationId, team.team().id(), team.ownerMember().id()),
                template(),
                false,
                actor.id(),
                NOW);
    }

    private AgentConfigurationVersion configuration(Optional<AgentMemoryPolicyReference> memory) {
        return AgentConfigurationVersion.createInitial(
                profile(),
                template(),
                Optional.of(actor.id()),
                Optional.empty(),
                Optional.of(AgentExecutionModelBinding.inheritTeamDefault()),
                Optional.empty(),
                Set.of(),
                memory,
                Optional.empty(),
                new PolicyPackReference(PolicyPackId.generate(), 1),
                SafeModelGenerateOptions.defaults(),
                actor.id(),
                NOW);
    }

    private void seedEntry(String key, String value, long generation) {
        seedEntry(key, value, generation, DEFAULT_REF.policyId(), DEFAULT_REF.version());
    }

    private void seedEntry(String key, String value, long generation, UUID policyId, long policyVersion) {
        memory.entries.add(AgentMemoryEntry.write(
                ownerKey(),
                new AgentMemoryPolicyReference(policyId, policyVersion),
                new AgentMemoryKey(key),
                value,
                generation,
                UtcTimestamp.from(NOW.value().plus(Duration.ofDays(90))),
                actor.id(),
                NOW));
    }

    private static TransactionExecutor directTransactions() {
        return new TransactionExecutor() {
            @Override
            public <T> T required(java.util.function.Supplier<T> operation) {
                return operation.get();
            }
        };
    }

    /** The guard pair, trimmed to member-level reads (no roles: memory is self-only). */
    private static final class Store implements TeamRepository, TeamMembershipQuery {
        private final TeamInitialization initialization;
        private List<TeamMember> members;
        private boolean uninitialized;

        private Store(TeamInitialization initialization) {
            this.initialization = initialization;
            this.members = List.of(initialization.ownerMember());
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
            if (!uninitialized) {
                return Optional.empty();
            }
            Team team = initialization.team();
            return Optional.of(new UninitializedTeam(
                    team.id(), team.organizationId(), team.name(),
                    TeamStatus.ACTIVE, team.version(), team.audit()));
        }

        @Override
        public List<TeamMember> findByTeam(OrganizationId organization, TeamId team) {
            return members;
        }
    }

    /**
     * Contract-grade in-memory fake: visibility = current generation + unexpired + exact
     * policy space, and upsert replays the FOR UPDATE generation check the adapter owns.
     */
    private static final class FakeAgentMemoryRepository implements AgentMemoryRepository {
        private final Map<AgentMemoryOwnerKey, AgentMemoryOwner> owners = new LinkedHashMap<>();
        private final List<AgentMemoryEntry> entries = new ArrayList<>();
        boolean clearOnNextUpsert;

        @Override
        public AgentMemoryOwner ensureOwner(
                AgentMemoryOwnerKey key, PrincipalId actor, UtcTimestamp now) {
            return owners.computeIfAbsent(
                    key, ignored -> new AgentMemoryOwner(key, 0, AuditMetadata.createdBy(actor, now)));
        }

        @Override
        public Optional<AgentMemoryOwner> findOwner(AgentMemoryOwnerKey key) {
            return Optional.ofNullable(owners.get(key));
        }

        @Override
        public List<AgentMemoryEntry> findVisible(
                AgentMemoryOwnerKey key, AgentMemoryPolicyReference policy, UtcTimestamp now) {
            long generation = findOwner(key).map(AgentMemoryOwner::clearanceGeneration).orElse(0L);
            return entries.stream()
                    .filter(entry -> entry.owner().equals(key))
                    .filter(entry -> entry.clearanceGeneration() == generation)
                    .filter(entry -> entry.policy().equals(policy))
                    .filter(entry -> entry.expiresAt().value().isAfter(now.value()))
                    .sorted(Comparator.comparing(entry -> entry.memoryKey().value()))
                    .toList();
        }

        @Override
        public AgentMemoryEntry upsert(AgentMemoryEntry entry) {
            if (clearOnNextUpsert) {
                clearOnNextUpsert = false;
                clear(entry.owner(), entry.owner().ownerPrincipalId(), NOW);
            }
            long generation = ensureOwner(
                    entry.owner(), entry.owner().ownerPrincipalId(), NOW).clearanceGeneration();
            if (entry.clearanceGeneration() != generation) {
                throw new OptimisticLockConflictException(
                        "AgentMemoryEntry",
                        entry.owner().agentProfileId() + ":" + entry.memoryKey().value(),
                        entry.clearanceGeneration(), generation);
            }
            for (int index = 0; index < entries.size(); index++) {
                AgentMemoryEntry existing = entries.get(index);
                if (existing.owner().equals(entry.owner())
                        && existing.policy().equals(entry.policy())
                        && existing.memoryKey().equals(entry.memoryKey())) {
                    AgentMemoryEntry replaced = existing.overwrite(
                            entry.value(), entry.expiresAt(),
                            entry.owner().ownerPrincipalId(), NOW);
                    entries.set(index, replaced);
                    return replaced;
                }
            }
            entries.add(entry);
            return entry;
        }

        @Override
        public boolean renew(
                AgentMemoryOwnerKey key, AgentMemoryPolicyReference policy,
                AgentMemoryKey memoryKey, UtcTimestamp newExpiresAt,
                PrincipalId actor, UtcTimestamp now) {
            for (int index = 0; index < entries.size(); index++) {
                AgentMemoryEntry entry = entries.get(index);
                if (entry.owner().equals(key)
                        && entry.policy().equals(policy)
                        && entry.memoryKey().equals(memoryKey)
                        && entry.expiresAt().value().isAfter(now.value())
                        && entry.clearanceGeneration() == findOwner(key)
                                .map(AgentMemoryOwner::clearanceGeneration).orElse(0L)) {
                    entries.set(index, entry.renewed(newExpiresAt, actor, now));
                    return true;
                }
            }
            return false;
        }

        @Override
        public AgentMemoryClearance clear(
                AgentMemoryOwnerKey key, PrincipalId actor, UtcTimestamp now) {
            AgentMemoryOwner owner = ensureOwner(key, actor, now);
            AgentMemoryOwner cleared = owner.cleared(actor, now);
            owners.put(key, cleared);
            long before = entries.size();
            entries.removeIf(entry -> entry.owner().equals(key)
                    && entry.clearanceGeneration() == owner.clearanceGeneration());
            return new AgentMemoryClearance(before - entries.size(), cleared.clearanceGeneration());
        }

        @Override
        public long deleteSweepable(UtcTimestamp now, int limit) {
            long deleted = 0;
            for (AgentMemoryEntry entry : List.copyOf(entries)) {
                if (deleted >= limit) {
                    break;
                }
                long generation = findOwner(entry.owner())
                        .map(AgentMemoryOwner::clearanceGeneration).orElse(-1L);
                if (entry.expiresAt().value().isBefore(now.value())
                        || entry.clearanceGeneration() != generation) {
                    entries.remove(entry);
                    deleted++;
                }
            }
            return deleted;
        }
    }
}
