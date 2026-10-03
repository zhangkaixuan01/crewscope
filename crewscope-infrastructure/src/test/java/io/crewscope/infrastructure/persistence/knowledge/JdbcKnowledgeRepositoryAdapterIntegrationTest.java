package io.crewscope.infrastructure.persistence.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.application.knowledge.KnowledgeEntryFilter;
import io.crewscope.application.knowledge.KnowledgeEntryPage;
import io.crewscope.application.knowledge.KnowledgeEntryPageRequest;
import io.crewscope.application.knowledge.KnowledgeEntryVersionPage;
import io.crewscope.application.knowledge.KnowledgeVersionPageRequest;
import io.crewscope.domain.knowledge.KnowledgeCategory;
import io.crewscope.domain.knowledge.KnowledgeEntry;
import io.crewscope.domain.knowledge.KnowledgeEntryId;
import io.crewscope.domain.knowledge.KnowledgeEntryKey;
import io.crewscope.domain.knowledge.KnowledgeEntryKeyConflictException;
import io.crewscope.domain.knowledge.KnowledgeEntryOrigin;
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
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A02a PostgreSQL proof: the D01 repository contract (optimistic head, append-only
 * versions, effective-pointer gate, tenant predicates) behind real SQLSTATE translation
 * and the two keyset cursors.
 */
@SpringBootTest(
        classes = JdbcKnowledgeRepositoryAdapterIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "crewscope.outbox.enabled=false"
        })
class JdbcKnowledgeRepositoryAdapterIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-10-01T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcKnowledgeRepositoryAdapter knowledge;

    private final OrganizationId organizationId = OrganizationId.generate();
    private final TeamId teamId = TeamId.generate();
    private final PrincipalId actor = PrincipalId.generate();
    private TeamScope scope;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcKnowledgeRepositoryAdapter.class)
    static class TestApplication {}

    @BeforeEach
    void seedTenant() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
        jdbc.update(
                "INSERT INTO crewscope.organization (id, name, status) VALUES (?, 'Knowledge Org', 'ACTIVE')",
                organizationId.value());
        jdbc.update(
                "INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, 'Knowledge Team', 'ACTIVE')",
                teamId.value(), organizationId.value());
        jdbc.update(
                """
                INSERT INTO crewscope.principal (id, organization_id, principal_type, display_name, status)
                VALUES (?, ?, 'USER', 'Knowledge owner', 'ACTIVE')
                """,
                actor.value(), organizationId.value());
        scope = new TeamScope(organizationId, teamId);
    }

    @Test
    void roundTripsTheFullLifecycleWithCategoryAndDraft() {
        KnowledgeEntry created = knowledge.create(entry("oncall-runbook", KnowledgeCategory.RUNBOOK));

        KnowledgeEntry restored = knowledge
                .findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(created.id(), restored.id());
        assertEquals(KnowledgeCategory.RUNBOOK, restored.category());
        assertEquals(KnowledgeEntryStatus.DRAFT, restored.status());
        assertEquals(0, restored.version());
        assertEquals("On-call runbook", restored.draft().orElseThrow().title());

        KnowledgeEntry edited = knowledge.save(
                restored.updateDraft("Revised runbook", "Step zero",
                        Optional.of(KnowledgeCategory.DECISION), actor, NOW),
                Optional.empty());
        assertEquals(1, edited.version());
        KnowledgeEntry reclassified = knowledge
                .findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(KnowledgeCategory.DECISION, reclassified.category());
        assertEquals("Revised runbook", reclassified.draft().orElseThrow().title());

        var publication = reclassified.publish(actor, NOW);
        knowledge.save(publication.entry(), Optional.of(publication.version()));
        KnowledgeEntry published = knowledge
                .findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(KnowledgeEntryStatus.PUBLISHED, published.status());
        assertEquals(Optional.of(new KnowledgeEntryRevision(1)), published.effectiveRevision());
        assertTrue(published.draft().isEmpty());

        KnowledgeEntryVersion version = knowledge
                .findVersion(organizationId, teamId, created.id(), new KnowledgeEntryRevision(1))
                .orElseThrow();
        assertEquals("Revised runbook", version.title());
        assertEquals(publication.version().contentHash(), version.contentHash());
        assertEquals(
                version.contentHash(),
                knowledge.findEffectiveVersion(organizationId, teamId, created.id())
                        .orElseThrow().contentHash());
    }

    @Test
    void roundTripsDistillationOriginAndKeepsItImmutableAcrossLifecycleSaves() {
        UUID executionId = UUID.randomUUID();
        KnowledgeEntry distilled = knowledge.create(
                KnowledgeEntry.createDistilled(
                        scope,
                        new KnowledgeEntryKey("distilled-postmortem"),
                        KnowledgeCategory.DECISION,
                        "Postmortem: cache eviction",
                        "Increase the eviction jitter",
                        new KnowledgeEntryOrigin(executionId, 2),
                        actor,
                        NOW));

        KnowledgeEntry restored = knowledge
                .findById(organizationId, teamId, distilled.id()).orElseThrow();
        KnowledgeEntryOrigin origin = restored.origin().orElseThrow();
        assertEquals(executionId, origin.taskExecutionId());
        assertEquals(2, origin.attempt());

        // Manual drafts never carry an origin; the column pair stays fully NULL.
        KnowledgeEntry manual = knowledge.create(entry("manual-note", KnowledgeCategory.GUIDE));
        assertTrue(knowledge.findById(organizationId, teamId, manual.id()).orElseThrow()
                .origin().isEmpty());

        // Every lifecycle UPDATE must carry the origin forward untouched (D3).
        KnowledgeEntry edited = knowledge.save(
                restored.updateDraft("Postmortem: cache eviction",
                        "Increase the eviction jitter and add a soak window",
                        Optional.empty(), actor, NOW),
                Optional.empty());
        var publication = edited.publish(actor, NOW);
        knowledge.save(publication.entry(), Optional.of(publication.version()));
        KnowledgeEntry published = knowledge
                .findById(organizationId, teamId, distilled.id()).orElseThrow();
        assertEquals(Optional.of(new KnowledgeEntryOrigin(executionId, 2)), published.origin());
        knowledge.save(published.retire(actor, NOW), Optional.empty());
        assertEquals(
                Optional.of(new KnowledgeEntryOrigin(executionId, 2)),
                knowledge.findById(organizationId, teamId, distilled.id()).orElseThrow().origin());
    }

    @Test
    void teamListingPagesByEntryKeyAscendingAndFiltersByCategory() {
        knowledge.create(entry("alpha-convention", KnowledgeCategory.CONVENTION));
        knowledge.create(entry("bravo-runbook", KnowledgeCategory.RUNBOOK));
        KnowledgeEntry charlie = knowledge.create(entry("charlie-guide", KnowledgeCategory.GUIDE));
        var charliePublication = charlie.publish(actor, NOW);
        knowledge.save(charliePublication.entry(), Optional.of(charliePublication.version()));

        KnowledgeEntryPage firstPage = knowledge.findByTeam(
                organizationId, teamId, KnowledgeEntryFilter.all(),
                new KnowledgeEntryPageRequest(Optional.empty(), 2));
        assertEquals(List.of("alpha-convention", "bravo-runbook"),
                firstPage.items().stream().map(item -> item.entryKey().value()).toList());
        assertEquals(Optional.of(new KnowledgeEntryKey("bravo-runbook")), firstPage.nextEntryKey());

        KnowledgeEntryPage secondPage = knowledge.findByTeam(
                organizationId, teamId, KnowledgeEntryFilter.all(),
                new KnowledgeEntryPageRequest(firstPage.nextEntryKey(), 2));
        assertEquals(List.of("charlie-guide"),
                secondPage.items().stream().map(item -> item.entryKey().value()).toList());
        assertTrue(secondPage.nextEntryKey().isEmpty());

        KnowledgeEntryPage runbooks = knowledge.findByTeam(
                organizationId, teamId,
                KnowledgeEntryFilter.byCategory(
                        Set.of(KnowledgeEntryStatus.DRAFT, KnowledgeEntryStatus.PUBLISHED,
                                KnowledgeEntryStatus.RETIRED, KnowledgeEntryStatus.DELETED),
                        KnowledgeCategory.RUNBOOK),
                new KnowledgeEntryPageRequest(Optional.empty(), 10));
        assertEquals(List.of("bravo-runbook"),
                runbooks.items().stream().map(item -> item.entryKey().value()).toList());
    }

    @Test
    void versionHistoryPagesByRevisionAscending() {
        KnowledgeEntry entry = knowledge.create(entry("deep-history", KnowledgeCategory.RUNBOOK));
        for (int index = 1; index <= 3; index++) {
            KnowledgeEntry head = knowledge
                    .findById(organizationId, teamId, entry.id()).orElseThrow();
            var publication = head.publish(actor, NOW);
            knowledge.save(publication.entry(), Optional.of(publication.version()));
            // Re-arm a draft so the next publish mints fresh content.
            KnowledgeEntry published = knowledge
                    .findById(organizationId, teamId, entry.id()).orElseThrow();
            knowledge.save(
                    published.updateDraft("Revision " + index, "Body " + index,
                            Optional.empty(), actor, NOW),
                    Optional.empty());
        }

        KnowledgeEntryVersionPage firstPage = knowledge.findVersionHistory(
                organizationId, teamId, entry.id(), new KnowledgeVersionPageRequest(Optional.empty(), 2));
        assertEquals(List.of(1L, 2L),
                firstPage.items().stream().map(item -> item.revision().value()).toList());
        assertEquals(Optional.of(new KnowledgeEntryRevision(2)), firstPage.nextRevision());

        KnowledgeEntryVersionPage secondPage = knowledge.findVersionHistory(
                organizationId, teamId, entry.id(),
                new KnowledgeVersionPageRequest(firstPage.nextRevision(), 2));
        assertEquals(List.of(3L),
                secondPage.items().stream().map(item -> item.revision().value()).toList());
        assertTrue(secondPage.nextRevision().isEmpty());
    }

    @Test
    void staleHeadVersionIsRejectedAndTheStoredHeadWins() {
        KnowledgeEntry created = knowledge.create(entry("stale-head", KnowledgeCategory.RUNBOOK));
        knowledge.save(
                created.updateDraft("Second draft", "Body", Optional.empty(), actor, NOW),
                Optional.empty());

        // Saving the original head again is one version behind the stored head.
        assertThrows(
                OptimisticLockConflictException.class,
                () -> knowledge.save(
                        created.updateDraft("Conflicting draft", "Body", Optional.empty(), actor, NOW),
                        Optional.empty()));
        KnowledgeEntry stored = knowledge.findById(organizationId, teamId, created.id()).orElseThrow();
        assertEquals(1, stored.version());
        assertEquals("Second draft", stored.draft().orElseThrow().title());
    }

    @Test
    void duplicateTenantKeyTranslatesToAKeyConflict() {
        knowledge.create(entry("dup-key", KnowledgeCategory.RUNBOOK));
        assertThrows(
                KnowledgeEntryKeyConflictException.class,
                () -> knowledge.create(entry("dup-key", KnowledgeCategory.GUIDE)));
    }

    @Test
    void republishingUnchangedContentTranslatesToAContentConflict() {
        KnowledgeEntry created = knowledge.create(entry("same-content", KnowledgeCategory.RUNBOOK));
        var publication = created.publish(actor, NOW);
        knowledge.save(publication.entry(), Optional.of(publication.version()));

        // Restore the identical draft and publish again: same hash must be rejected.
        KnowledgeEntry published = knowledge.findById(organizationId, teamId, created.id()).orElseThrow();
        KnowledgeEntry rearmed = knowledge.save(
                published.updateDraft("On-call runbook", "Step one", Optional.empty(), actor, NOW),
                Optional.empty());
        assertThrows(
                KnowledgeVersionContentConflictException.class,
                () -> knowledge.save(rearmed.publish(actor, NOW).entry(),
                        Optional.of(rearmed.publish(actor, NOW).version())));
    }

    @Test
    void effectiveVersionsFollowTheHeadPointerOnly() {
        KnowledgeEntry draft = knowledge.create(entry("still-draft", KnowledgeCategory.RUNBOOK));
        assertTrue(knowledge.findEffectiveVersion(organizationId, teamId, draft.id()).isEmpty());

        KnowledgeEntry entry = knowledge.create(entry("pointer-entry", KnowledgeCategory.RUNBOOK));
        var publication = entry.publish(actor, NOW);
        knowledge.save(publication.entry(), Optional.of(publication.version()));
        assertEquals(
                Optional.of(new KnowledgeEntryRevision(1)),
                knowledge.findEffectiveVersion(organizationId, teamId, entry.id())
                        .map(KnowledgeEntryVersion::revision));

        // Publish the other entry too, so the team listing proves a retired head drops
        // out while a live published head stays in — not merely that the list is empty.
        var draftPublication = draft.publish(actor, NOW);
        knowledge.save(draftPublication.entry(), Optional.of(draftPublication.version()));

        KnowledgeEntry published = knowledge.findById(organizationId, teamId, entry.id()).orElseThrow();
        knowledge.save(published.retire(actor, NOW), Optional.empty());
        assertTrue(knowledge.findEffectiveVersion(organizationId, teamId, entry.id()).isEmpty());
        List<KnowledgeEntryVersion> effective =
                knowledge.findEffectiveVersionsByTeam(organizationId, teamId);
        assertEquals(1, effective.size());
        assertEquals(draft.id(), effective.get(0).entryId());
        assertEquals(new KnowledgeEntryRevision(1), effective.get(0).revision());
    }

    @Test
    void crossTenantQueriesStayEmpty() {
        KnowledgeEntry entry = knowledge.create(entry("tenant-guard", KnowledgeCategory.RUNBOOK));
        var publication = entry.publish(actor, NOW);
        knowledge.save(publication.entry(), Optional.of(publication.version()));
        OrganizationId stranger = OrganizationId.generate();
        TeamId strangerTeam = TeamId.generate();

        assertTrue(knowledge.findById(stranger, teamId, entry.id()).isEmpty());
        assertTrue(knowledge.findByKey(stranger, teamId, entry.entryKey()).isEmpty());
        assertTrue(knowledge.findByTeam(
                stranger, teamId, KnowledgeEntryFilter.all(),
                new KnowledgeEntryPageRequest(Optional.empty(), 10)).items().isEmpty());
        assertTrue(knowledge.findVersion(
                stranger, teamId, entry.id(), new KnowledgeEntryRevision(1)).isEmpty());
        assertTrue(knowledge.findVersion(
                organizationId, strangerTeam, entry.id(), new KnowledgeEntryRevision(1)).isEmpty());
        assertTrue(knowledge.findEffectiveVersion(stranger, teamId, entry.id()).isEmpty());
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM crewscope.knowledge_entry_version WHERE entry_id = ?",
                        Long.class, entry.id().value()));
    }

    private KnowledgeEntry entry(String entryKey, KnowledgeCategory category) {
        return KnowledgeEntry.create(
                scope,
                new KnowledgeEntryKey(entryKey),
                category,
                "On-call runbook",
                "Step one",
                actor,
                NOW);
    }
}
