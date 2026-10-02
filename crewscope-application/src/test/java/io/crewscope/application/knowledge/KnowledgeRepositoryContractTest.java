package io.crewscope.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryRevision;
import io.crewscope.domain.knowledge.KnowledgeEntryStatus;
import io.crewscope.domain.knowledge.KnowledgeEntryVersion;
import io.crewscope.domain.knowledge.KnowledgeVersionContentConflictException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Executable contract for the future PostgreSQL knowledge adapter (A02): tenant
 * scoping, version concurrency and source attribution must hold before any SQL exists.
 */
class KnowledgeRepositoryContractTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-01T09:00:00Z");
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();

    @Test
    void scopesEntriesAndUniquenessToTheCompleteTeamCoordinate() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeEntry first = repository.create(entry("deploy-runbook"));

        repository.create(entryInScope(
                new TeamScope(ORGANIZATION_ID, TeamId.generate()), "deploy-runbook"));

        assertThrows(
                KnowledgeEntryKeyConflictException.class,
                () -> repository.create(entry("deploy-runbook")));

        assertEquals(
                first.id(),
                repository
                        .findByKey(ORGANIZATION_ID, TEAM_ID, KnowledgeEntryKey.parse("deploy-runbook"))
                        .orElseThrow()
                        .id());
        assertEquals(
                Optional.empty(),
                repository.findById(OrganizationId.generate(), TEAM_ID, first.id()));
        assertEquals(
                Optional.empty(),
                repository.findByKey(
                        ORGANIZATION_ID,
                        TeamId.generate(),
                        KnowledgeEntryKey.parse("deploy-runbook")));
    }

    @Test
    void lateWriterOnTheSameHeadVersionFailsWithAnOptimisticLockConflict() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeEntry created = repository.create(entry("deploy-runbook"));
        KnowledgeEntry staleReader = repository
                .findById(ORGANIZATION_ID, TEAM_ID, created.id())
                .orElseThrow();

        repository.save(
                staleReader.publish(PrincipalId.generate(), NOW).entry(),
                Optional.empty());

        assertThrows(
                OptimisticLockConflictException.class,
                () -> repository.save(
                        staleReader.publish(PrincipalId.generate(), NOW).entry(),
                        Optional.empty()));
        assertEquals(
                1L,
                repository.findById(ORGANIZATION_ID, TEAM_ID, created.id()).orElseThrow().version());
    }

    @Test
    void publishingPersistsHeadAndVersionAtomically() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeEntry created = repository.create(entry("deploy-runbook"));

        var publication = created.publish(PrincipalId.generate(), NOW);
        repository.save(publication.entry(), Optional.of(publication.version()));

        KnowledgeEntry stored = repository
                .findById(ORGANIZATION_ID, TEAM_ID, created.id())
                .orElseThrow();
        assertEquals(KnowledgeEntryStatus.PUBLISHED, stored.status());
        assertEquals(new KnowledgeEntryRevision(1L), stored.effectiveRevision().orElseThrow());

        Optional<KnowledgeEntryVersion> version = repository.findVersion(
                ORGANIZATION_ID, TEAM_ID, created.id(), new KnowledgeEntryRevision(1L));
        assertEquals(publication.version().contentHash(), version.orElseThrow().contentHash());
        assertEquals(
                List.of(new KnowledgeEntryRevision(1L)),
                repository.findVersionHistory(ORGANIZATION_ID, TEAM_ID, created.id()).stream()
                        .map(KnowledgeEntryVersion::revision)
                        .toList());
    }

    @Test
    void republishingUnchangedContentIsAnExplicitConflict() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeEntry created = repository.create(entry("deploy-runbook"));
        var first = created.publish(PrincipalId.generate(), NOW);
        repository.save(first.entry(), Optional.of(first.version()));

        KnowledgeEntryVersion duplicate = KnowledgeEntryVersion.create(
                created.id(),
                new TeamScope(ORGANIZATION_ID, TEAM_ID),
                new KnowledgeEntryRevision(2L),
                Optional.of(new KnowledgeEntryRevision(1L)),
                "Deploy Runbook",
                "Step one: drain the pool.",
                PrincipalId.generate(),
                NOW);

        assertThrows(
                KnowledgeVersionContentConflictException.class,
                () -> repository.save(
                        first.entry().updateDraft(
                                "Deploy Runbook", "Step one: drain the pool.",
                                PrincipalId.generate(), NOW),
                        Optional.of(duplicate)));
    }

    @Test
    void effectiveVersionGateOnlyResolvesPublishedPointerVersions() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeEntry created = repository.create(entry("deploy-runbook"));

        assertTrue(
                repository.findEffectiveVersion(ORGANIZATION_ID, TEAM_ID, created.id()).isEmpty());

        var first = created.publish(PrincipalId.generate(), NOW);
        repository.save(first.entry(), Optional.of(first.version()));
        assertEquals(
                new KnowledgeEntryRevision(1L),
                repository
                        .findEffectiveVersion(ORGANIZATION_ID, TEAM_ID, created.id())
                        .orElseThrow()
                        .revision());

        KnowledgeEntry redrafted = first.entry()
                .updateDraft("Deploy Runbook v2", "Step one: drain, then verify.",
                        PrincipalId.generate(), NOW);
        repository.save(redrafted, Optional.empty());
        var second = redrafted.publish(PrincipalId.generate(), NOW);
        repository.save(second.entry(), Optional.of(second.version()));
        assertEquals(
                new KnowledgeEntryRevision(2L),
                repository
                        .findEffectiveVersion(ORGANIZATION_ID, TEAM_ID, created.id())
                        .orElseThrow()
                        .revision());
        assertEquals(
                2,
                repository.findVersionHistory(ORGANIZATION_ID, TEAM_ID, created.id()).size());

        KnowledgeEntry retired = second.entry().retire(PrincipalId.generate(), NOW);
        repository.save(retired, Optional.empty());
        assertTrue(
                repository.findEffectiveVersion(ORGANIZATION_ID, TEAM_ID, created.id()).isEmpty());
        assertEquals(
                2,
                repository.findVersionHistory(ORGANIZATION_ID, TEAM_ID, created.id()).size());

        repository.save(retired.delete(PrincipalId.generate(), NOW), Optional.empty());
        assertTrue(
                repository.findEffectiveVersion(ORGANIZATION_ID, TEAM_ID, created.id()).isEmpty());
    }

    @Test
    void effectiveVersionsByTeamSeedOnlyRetrievableEntries() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        var published = repository.create(entry("deploy-runbook"))
                .publish(PrincipalId.generate(), NOW);
        repository.save(published.entry(), Optional.of(published.version()));
        repository.create(entry("onboarding-guide"));

        assertEquals(
                List.of("deploy-runbook"),
                repository.findEffectiveVersionsByTeam(ORGANIZATION_ID, TEAM_ID).stream()
                        .map(version -> repository
                                .findById(ORGANIZATION_ID, TEAM_ID, version.entryId())
                                .orElseThrow()
                                .entryKey()
                                .value())
                        .toList());
        assertTrue(
                repository
                        .findEffectiveVersionsByTeam(OrganizationId.generate(), TEAM_ID)
                        .isEmpty());
    }

    @Test
    void statusFilterDrivesTeamListings() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        repository.create(entry("deploy-runbook"));

        assertEquals(
                1,
                repository
                        .findByTeam(ORGANIZATION_ID, TEAM_ID, KnowledgeEntryFilter.all())
                        .size());
        assertEquals(
                0,
                repository
                        .findByTeam(
                                ORGANIZATION_ID, TEAM_ID, KnowledgeEntryFilter.effectivelyPublished())
                        .size());
    }

    private static KnowledgeEntry entry(String entryKey) {
        return entryInScope(new TeamScope(ORGANIZATION_ID, TEAM_ID), entryKey);
    }

    private static KnowledgeEntry entryInScope(TeamScope scope, String entryKey) {
        return KnowledgeEntry.create(
                scope,
                KnowledgeEntryKey.parse(entryKey),
                "Deploy Runbook",
                "Step one: drain the pool.",
                PrincipalId.generate(),
                NOW);
    }

    /** Contract-grade in-memory fake: atomic save, optimistic head, tenant scoping. */
    private static final class InMemoryKnowledgeRepository implements KnowledgeRepository {

        private final Map<KnowledgeEntryId, KnowledgeEntry> entries = new HashMap<>();
        private final Map<KnowledgeEntryId, List<KnowledgeEntryVersion>> versions = new HashMap<>();

        @Override
        public KnowledgeEntry create(KnowledgeEntry entry) {
            findByKey(entry.scope().organizationId(), entry.scope().teamId(), entry.entryKey())
                    .ifPresent(ignored -> {
                        throw new KnowledgeEntryKeyConflictException(entry.scope(), entry.entryKey());
                    });
            entries.put(entry.id(), entry);
            versions.put(entry.id(), new ArrayList<>());
            return entry;
        }

        @Override
        public KnowledgeEntry save(
                KnowledgeEntry entry, Optional<KnowledgeEntryVersion> appendedVersion) {
            KnowledgeEntry stored = requireExisting(entry.id());
            if (entry.version() != stored.version() + 1) {
                throw new OptimisticLockConflictException(
                        "KnowledgeEntry", entry.id(), entry.version() - 1, stored.version());
            }
            appendedVersion.ifPresent(version -> {
                if (!version.entryId().equals(entry.id())
                        || !version.scope().equals(entry.scope())) {
                    throw new IllegalArgumentException(
                            "appended version must belong to the saving entry's scope");
                }
                versions.getOrDefault(entry.id(), List.of()).stream()
                        .filter(existing -> existing.revision().equals(version.revision()))
                        .findFirst()
                        .ifPresent(existing -> {
                            throw new OptimisticLockConflictException(
                                    "KnowledgeEntryVersion",
                                    entry.id(),
                                    version.revision().value(),
                                    existing.revision().value());
                        });
                versions.getOrDefault(entry.id(), List.of()).stream()
                        .filter(existing -> existing.contentHash().equals(version.contentHash()))
                        .findFirst()
                        .ifPresent(existing -> {
                            throw new KnowledgeVersionContentConflictException(
                                    entry.id(), version.revision(), version.contentHash());
                        });
            });
            entries.put(entry.id(), entry);
            appendedVersion.ifPresent(version -> versions.get(entry.id()).add(version));
            return entry;
        }

        @Override
        public Optional<KnowledgeEntry> findById(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return Optional.ofNullable(entries.get(entryId))
                    .filter(entry -> inScope(entry, organizationId, teamId));
        }

        @Override
        public Optional<KnowledgeEntry> findByKey(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryKey entryKey) {
            return entries.values().stream()
                    .filter(entry -> inScope(entry, organizationId, teamId))
                    .filter(entry -> entry.entryKey().equals(entryKey))
                    .findFirst();
        }

        @Override
        public List<KnowledgeEntry> findByTeam(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryFilter filter) {
            return entries.values().stream()
                    .filter(entry -> inScope(entry, organizationId, teamId))
                    .filter(entry -> filter.statuses().contains(entry.status()))
                    .sorted(Comparator.comparing(entry -> entry.entryKey().value()))
                    .toList();
        }

        @Override
        public Optional<KnowledgeEntryVersion> findVersion(
                OrganizationId organizationId,
                TeamId teamId,
                KnowledgeEntryId entryId,
                KnowledgeEntryRevision revision) {
            return findVersionHistory(organizationId, teamId, entryId).stream()
                    .filter(version -> version.revision().equals(revision))
                    .findFirst();
        }

        @Override
        public List<KnowledgeEntryVersion> findVersionHistory(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return findById(organizationId, teamId, entryId)
                    .map(entry -> versions.getOrDefault(entry.id(), List.of()).stream()
                            .sorted(Comparator.comparing(version -> version.revision().value()))
                            .toList())
                    .orElseGet(List::of);
        }

        @Override
        public Optional<KnowledgeEntryVersion> findEffectiveVersion(
                OrganizationId organizationId, TeamId teamId, KnowledgeEntryId entryId) {
            return findById(organizationId, teamId, entryId)
                    .filter(KnowledgeEntry::effectivelyPublished)
                    .flatMap(entry -> findVersion(
                            organizationId, teamId, entryId, entry.effectiveRevision().orElseThrow()));
        }

        @Override
        public List<KnowledgeEntryVersion> findEffectiveVersionsByTeam(
                OrganizationId organizationId, TeamId teamId) {
            return entries.values().stream()
                    .filter(entry -> inScope(entry, organizationId, teamId))
                    .filter(KnowledgeEntry::effectivelyPublished)
                    .map(entry -> findVersion(
                            organizationId, teamId, entry.id(), entry.effectiveRevision().orElseThrow()))
                    .flatMap(Optional::stream)
                    .toList();
        }

        private KnowledgeEntry requireExisting(KnowledgeEntryId entryId) {
            KnowledgeEntry stored = entries.get(entryId);
            if (stored == null) {
                throw new IllegalArgumentException("unknown entry " + entryId);
            }
            return stored;
        }

        private static boolean inScope(
                KnowledgeEntry entry, OrganizationId organizationId, TeamId teamId) {
            return entry.scope().organizationId().equals(organizationId)
                    && entry.scope().teamId().equals(teamId);
        }
    }
}
