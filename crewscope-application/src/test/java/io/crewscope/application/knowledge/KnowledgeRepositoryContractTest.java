package io.crewscope.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.knowledge.KnowledgeCategory;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Executable contract for the PostgreSQL knowledge adapter: tenant scoping, version
 * concurrency and source attribution must hold before any SQL exists, and the JDBC
 * adapter must repeat the same semantics (plus the keyset pagination contract).
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
                repository
                        .findVersionHistory(ORGANIZATION_ID, TEAM_ID, created.id(),
                                new KnowledgeVersionPageRequest(Optional.empty(), 100))
                        .items().stream()
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
                                Optional.empty(), PrincipalId.generate(), NOW),
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
                        Optional.empty(), PrincipalId.generate(), NOW);
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
                repository
                        .findVersionHistory(ORGANIZATION_ID, TEAM_ID, created.id(),
                                new KnowledgeVersionPageRequest(Optional.empty(), 100))
                        .items().size());

        KnowledgeEntry retired = second.entry().retire(PrincipalId.generate(), NOW);
        repository.save(retired, Optional.empty());
        assertTrue(
                repository.findEffectiveVersion(ORGANIZATION_ID, TEAM_ID, created.id()).isEmpty());
        assertEquals(
                2,
                repository
                        .findVersionHistory(ORGANIZATION_ID, TEAM_ID, created.id(),
                                new KnowledgeVersionPageRequest(Optional.empty(), 100))
                        .items().size());

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
                        .findByTeam(ORGANIZATION_ID, TEAM_ID, KnowledgeEntryFilter.all(),
                                new KnowledgeEntryPageRequest(Optional.empty(), 50))
                        .items().size());
        assertEquals(
                0,
                repository
                        .findByTeam(
                                ORGANIZATION_ID, TEAM_ID, KnowledgeEntryFilter.effectivelyPublished(),
                                new KnowledgeEntryPageRequest(Optional.empty(), 50))
                        .items().size());
    }

    @Test
    void teamListingPagesByEntryKeyAscendingAndFiltersByCategory() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        repository.create(entry("deploy-runbook", KnowledgeCategory.RUNBOOK));
        repository.create(entry("incident-guide", KnowledgeCategory.GUIDE));
        repository.create(entry("review-conventions", KnowledgeCategory.CONVENTION));
        repository.create(entry("tech-decisions", KnowledgeCategory.DECISION));

        KnowledgeEntryPage firstPage = repository.findByTeam(
                ORGANIZATION_ID, TEAM_ID, KnowledgeEntryFilter.all(),
                new KnowledgeEntryPageRequest(Optional.empty(), 2));
        assertEquals(
                List.of("deploy-runbook", "incident-guide"),
                firstPage.items().stream().map(entry -> entry.entryKey().value()).toList());
        assertEquals("incident-guide", firstPage.nextEntryKey().orElseThrow().value());

        KnowledgeEntryPage secondPage = repository.findByTeam(
                ORGANIZATION_ID, TEAM_ID, KnowledgeEntryFilter.all(),
                new KnowledgeEntryPageRequest(firstPage.nextEntryKey(), 2));
        assertEquals(
                List.of("review-conventions", "tech-decisions"),
                secondPage.items().stream().map(entry -> entry.entryKey().value()).toList());
        assertTrue(secondPage.nextEntryKey().isEmpty());

        KnowledgeEntryPage guideOnly = repository.findByTeam(
                ORGANIZATION_ID, TEAM_ID,
                KnowledgeEntryFilter.byCategory(
                        java.util.EnumSet.allOf(KnowledgeEntryStatus.class),
                        KnowledgeCategory.GUIDE),
                new KnowledgeEntryPageRequest(Optional.empty(), 50));
        assertEquals(
                List.of("incident-guide"),
                guideOnly.items().stream().map(entry -> entry.entryKey().value()).toList());
        assertTrue(guideOnly.nextEntryKey().isEmpty());
    }

    @Test
    void versionHistoryPagesByRevisionAscending() {
        InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
        KnowledgeEntry created = repository.create(entry("deploy-runbook"));
        KnowledgeEntry head = created;
        for (int index = 1; index <= 3; index++) {
            KnowledgeEntry redrafted = head
                    .updateDraft("Deploy Runbook v" + index, "Step " + index + ": content.",
                            Optional.empty(), PrincipalId.generate(), NOW);
            repository.save(redrafted, Optional.empty());
            var publication = redrafted.publish(PrincipalId.generate(), NOW);
            repository.save(publication.entry(), Optional.of(publication.version()));
            head = publication.entry();
        }

        KnowledgeEntryVersionPage firstPage = repository.findVersionHistory(
                ORGANIZATION_ID, TEAM_ID, created.id(),
                new KnowledgeVersionPageRequest(Optional.empty(), 2));
        assertEquals(
                List.of(1L, 2L),
                firstPage.items().stream().map(version -> version.revision().value()).toList());
        assertEquals(2L, firstPage.nextRevision().orElseThrow().value());

        KnowledgeEntryVersionPage secondPage = repository.findVersionHistory(
                ORGANIZATION_ID, TEAM_ID, created.id(),
                new KnowledgeVersionPageRequest(firstPage.nextRevision(), 2));
        assertEquals(
                List.of(3L),
                secondPage.items().stream().map(version -> version.revision().value()).toList());
        assertTrue(secondPage.nextRevision().isEmpty());
    }

    private static KnowledgeEntry entry(String entryKey) {
        return entry(entryKey, KnowledgeCategory.RUNBOOK);
    }

    private static KnowledgeEntry entry(String entryKey, KnowledgeCategory category) {
        return entryInScope(new TeamScope(ORGANIZATION_ID, TEAM_ID), entryKey, category);
    }

    private static KnowledgeEntry entryInScope(TeamScope scope, String entryKey) {
        return entryInScope(scope, entryKey, KnowledgeCategory.RUNBOOK);
    }

    private static KnowledgeEntry entryInScope(
            TeamScope scope, String entryKey, KnowledgeCategory category) {
        return KnowledgeEntry.create(
                scope,
                KnowledgeEntryKey.parse(entryKey),
                category,
                "Deploy Runbook",
                "Step one: drain the pool.",
                PrincipalId.generate(),
                NOW);
    }
}
