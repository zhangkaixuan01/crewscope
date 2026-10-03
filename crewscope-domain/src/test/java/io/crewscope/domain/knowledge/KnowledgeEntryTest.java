package io.crewscope.domain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeEntryTest {

    private static final TeamScope SCOPE =
            new TeamScope(OrganizationId.generate(), TeamId.generate());
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final KnowledgeEntryKey KEY = KnowledgeEntryKey.parse("deploy-runbook");
    private static final UtcTimestamp T0 = UtcTimestamp.parse("2026-10-01T09:00:00Z");
    private static final UtcTimestamp T1 = UtcTimestamp.parse("2026-10-01T09:01:00Z");
    private static final UtcTimestamp T2 = UtcTimestamp.parse("2026-10-01T09:02:00Z");

    private static KnowledgeEntry draft() {
        return KnowledgeEntry.create(
                SCOPE, KEY, KnowledgeCategory.RUNBOOK,
                "Deploy Runbook", "Step one: drain the pool.", ACTOR, T0);
    }

    @Test
    void createYieldsDraftThatIsNotRetrievable() {
        KnowledgeEntry entry = draft();

        assertEquals(KnowledgeEntryStatus.DRAFT, entry.status());
        assertEquals(KnowledgeCategory.RUNBOOK, entry.category());
        assertFalse(entry.effectivelyPublished());
        assertEquals(0L, entry.latestRevision());
        assertEquals(0L, entry.version());
        assertTrue(entry.draft().isPresent());
        assertTrue(entry.effectiveRevision().isEmpty());
    }

    @Test
    void publishAppendsFirstRevisionAndMovesPointerAtomically() {
        KnowledgeEntryPublication publication = draft().publish(ACTOR, T1);

        KnowledgeEntryVersion version = publication.version();
        KnowledgeEntry updated = publication.entry();

        assertEquals(new KnowledgeEntryRevision(1L), version.revision());
        assertTrue(version.previousRevision().isEmpty());
        assertEquals("Deploy Runbook", version.title());
        assertEquals(
                KnowledgeContentHash.of("Deploy Runbook", "Step one: drain the pool."),
                version.contentHash());
        assertEquals(updated.id(), version.entryId());
        assertEquals(updated.scope(), version.scope());

        assertTrue(updated.effectivelyPublished());
        assertEquals(KnowledgeCategory.RUNBOOK, updated.category());
        assertEquals(new KnowledgeEntryRevision(1L), updated.effectiveRevision().orElseThrow());
        assertEquals(1L, updated.latestRevision());
        assertEquals(1L, updated.version());
        assertTrue(updated.draft().isEmpty());
    }

    @Test
    void consecutivePublishesAdvanceThePointerMonotonically() {
        KnowledgeEntry published = draft().publish(ACTOR, T1).entry();

        KnowledgeEntryPublication second = published
                .updateDraft("Deploy Runbook v2", "Step one: drain, then verify.",
                        Optional.empty(), ACTOR, T2)
                .publish(ACTOR, T2);

        assertEquals(new KnowledgeEntryRevision(2L), second.version().revision());
        assertEquals(new KnowledgeEntryRevision(1L), second.version().previousRevision().orElseThrow());
        assertEquals(new KnowledgeEntryRevision(2L), second.entry().effectiveRevision().orElseThrow());
        assertEquals(2L, second.entry().latestRevision());
        assertEquals(3L, second.entry().version());
    }

    @Test
    void retireStopsRetrievalButRetainsLastEffectiveRevision() {
        KnowledgeEntry retired = draft().publish(ACTOR, T1).entry().retire(ACTOR, T2);

        assertEquals(KnowledgeEntryStatus.RETIRED, retired.status());
        assertFalse(retired.effectivelyPublished());
        assertEquals(new KnowledgeEntryRevision(1L), retired.lastEffectiveRevision().orElseThrow());
        assertEquals(1L, retired.latestRevision());
    }

    @Test
    void retiredEntryCanBeRevivedByPublishingANewRevision() {
        KnowledgeEntry revived = draft().publish(ACTOR, T1).entry()
                .retire(ACTOR, T2)
                .updateDraft("Deploy Runbook v2", "Step one: drain, then verify.",
                        Optional.empty(), ACTOR, T2)
                .publish(ACTOR, T2).entry();

        assertTrue(revived.effectivelyPublished());
        assertEquals(new KnowledgeEntryRevision(2L), revived.effectiveRevision().orElseThrow());
    }

    @Test
    void deletedTombstoneCanNeverBePublishedAgain() {
        KnowledgeEntry tombstone = draft().publish(ACTOR, T1).entry().delete(ACTOR, T2);

        assertEquals(KnowledgeEntryStatus.DELETED, tombstone.status());
        assertFalse(tombstone.effectivelyPublished());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> tombstone.publish(ACTOR, T2));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> tombstone.updateDraft("zombie", "revival attempt",
                        Optional.empty(), ACTOR, T2));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> tombstone.delete(ACTOR, T2));
    }

    @Test
    void retireIsOnlyReachableFromPublished() {
        assertThrows(
                InvalidStateTransitionException.class,
                () -> draft().retire(ACTOR, T1));

        KnowledgeEntry retired = draft().publish(ACTOR, T1).entry().retire(ACTOR, T2);
        assertThrows(
                InvalidStateTransitionException.class,
                () -> retired.retire(ACTOR, T2));
    }

    @Test
    void updateDraftNeverTouchesTheEffectivePointer() {
        KnowledgeEntry updated = draft().publish(ACTOR, T1).entry()
                .updateDraft("Deploy Runbook v2", "Step one: drain, then verify.",
                        Optional.empty(), ACTOR, T2);

        assertEquals(KnowledgeEntryStatus.PUBLISHED, updated.status());
        assertTrue(updated.effectivelyPublished());
        assertEquals(new KnowledgeEntryRevision(1L), updated.effectiveRevision().orElseThrow());
        assertEquals(1L, updated.latestRevision());
        assertTrue(updated.draft().isPresent());
    }

    @Test
    void updateDraftMayReclassifyWithoutMintingAVersion() {
        KnowledgeEntry recategorized = draft()
                .updateDraft("Deploy Runbook v2", "Step one: drain, then verify.",
                        Optional.of(KnowledgeCategory.GUIDE), ACTOR, T1);

        assertEquals(KnowledgeCategory.GUIDE, recategorized.category());
        assertEquals(1L, recategorized.version());
        assertEquals(0L, recategorized.latestRevision());

        KnowledgeEntry kept = draft()
                .updateDraft("Deploy Runbook v2", "Step one: drain, then verify.",
                        Optional.empty(), ACTOR, T1);

        assertEquals(KnowledgeCategory.RUNBOOK, kept.category());
    }

    @Test
    void publishWithoutDraftIsRejected() {
        KnowledgeEntry published = draft().publish(ACTOR, T1).entry();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class, () -> published.publish(ACTOR, T2));

        assertEquals("knowledgeEntry.draft", failure.error().details().get("field"));
    }

    @Test
    void reconstituteRejectsShapeViolationsWithFieldPaths() {
        KnowledgeEntry draft = draft();

        DomainValidationException draftWithPointer = assertThrows(
                DomainValidationException.class,
                () -> KnowledgeEntry.reconstitute(
                        draft.id(), SCOPE, KEY, KnowledgeCategory.RUNBOOK, Optional.empty(),
                        KnowledgeEntryStatus.DRAFT,
                        Optional.of(new KnowledgeEntryRevision(1L)), 1L,
                        draft.draft(), 0L, draft.audit()));
        assertEquals("knowledgeEntry.effectiveRevision", draftWithPointer.error().details().get("field"));

        DomainValidationException publishedWithoutPointer = assertThrows(
                DomainValidationException.class,
                () -> KnowledgeEntry.reconstitute(
                        draft.id(), SCOPE, KEY, KnowledgeCategory.RUNBOOK, Optional.empty(),
                        KnowledgeEntryStatus.PUBLISHED,
                        Optional.empty(), 1L, Optional.empty(), 1L, draft.audit()));
        assertEquals(
                "knowledgeEntry.effectiveRevision",
                publishedWithoutPointer.error().details().get("field"));

        DomainValidationException pointerBeyondLatest = assertThrows(
                DomainValidationException.class,
                () -> KnowledgeEntry.reconstitute(
                        draft.id(), SCOPE, KEY, KnowledgeCategory.RUNBOOK, Optional.empty(),
                        KnowledgeEntryStatus.PUBLISHED,
                        Optional.of(new KnowledgeEntryRevision(3L)), 2L,
                        Optional.empty(), 1L, draft.audit()));
        assertEquals(
                "knowledgeEntry.effectiveRevision",
                pointerBeyondLatest.error().details().get("field"));
    }

    @Test
    void reconstituteAcceptsPersistedHeadsWithRetainedEffectiveRevision() {
        KnowledgeEntry retired = draft().publish(ACTOR, T1).entry().retire(ACTOR, T2);

        KnowledgeEntry restored = KnowledgeEntry.reconstitute(
                retired.id(), retired.scope(), retired.entryKey(), retired.category(),
                retired.origin(), retired.status(), retired.effectiveRevision(),
                retired.latestRevision(), retired.draft(), retired.version(), retired.audit());

        assertEquals(retired.id(), restored.id());
        assertEquals(retired.scope(), restored.scope());
        assertEquals(retired.entryKey(), restored.entryKey());
        assertEquals(retired.category(), restored.category());
        assertEquals(retired.status(), restored.status());
        assertEquals(retired.effectiveRevision(), restored.effectiveRevision());
        assertEquals(retired.latestRevision(), restored.latestRevision());
        assertEquals(retired.draft(), restored.draft());
        assertEquals(retired.version(), restored.version());
        assertEquals(retired.audit(), restored.audit());
    }

    @Test
    void createDistilledCarriesImmutableOriginThroughEveryCommand() {
        KnowledgeEntryOrigin origin =
                new KnowledgeEntryOrigin(UUID.randomUUID(), 2);
        KnowledgeEntry distilled = KnowledgeEntry.createDistilled(
                SCOPE, KEY, KnowledgeCategory.DECISION,
                "Migrated convention", "Prefer keyset pagination.", origin, ACTOR, T0);

        assertEquals(Optional.of(origin), distilled.origin());
        assertEquals(KnowledgeEntryStatus.DRAFT, distilled.status());
        assertTrue(distilled.draft().isPresent());

        KnowledgeEntry edited = distilled.updateDraft(
                "Migrated convention", "Prefer keyset pagination everywhere.",
                Optional.empty(), ACTOR, T1);
        KnowledgeEntry published = edited.publish(ACTOR, T1).entry();
        KnowledgeEntry retired = published.retire(ACTOR, T2);
        KnowledgeEntry tombstone = retired.delete(ACTOR, T2);

        assertEquals(Optional.of(origin), edited.origin());
        assertEquals(Optional.of(origin), published.origin());
        assertEquals(Optional.of(origin), retired.origin());
        assertEquals(Optional.of(origin), tombstone.origin());

        KnowledgeEntry manual = KnowledgeEntry.create(
                SCOPE, KEY, KnowledgeCategory.RUNBOOK,
                "Manual runbook", "Step one.", ACTOR, T0);
        assertTrue(manual.origin().isEmpty());
    }

    @Test
    void createDistilledRejectsInvalidOrigin() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeEntryOrigin(UUID.randomUUID(), 0));
        assertThrows(
                NullPointerException.class,
                () -> new KnowledgeEntryOrigin(null, 1));
        assertThrows(
                NullPointerException.class,
                () -> KnowledgeEntry.createDistilled(
                        SCOPE, KEY, KnowledgeCategory.DECISION,
                        "Title", "Content", null, ACTOR, T0));
    }

    @Test
    void reconstituteRoundTripsTheOriginField() {
        KnowledgeEntryOrigin origin =
                new KnowledgeEntryOrigin(UUID.randomUUID(), 3);
        KnowledgeEntry distilled = KnowledgeEntry.createDistilled(
                SCOPE, KEY, KnowledgeCategory.GUIDE,
                "Review checklist", "Check the diff line count.", origin, ACTOR, T0);

        KnowledgeEntry restored = KnowledgeEntry.reconstitute(
                distilled.id(), distilled.scope(), distilled.entryKey(),
                distilled.category(), distilled.origin(), distilled.status(),
                distilled.effectiveRevision(), distilled.latestRevision(),
                distilled.draft(), distilled.version(), distilled.audit());

        assertEquals(Optional.of(origin), restored.origin());
    }
}
