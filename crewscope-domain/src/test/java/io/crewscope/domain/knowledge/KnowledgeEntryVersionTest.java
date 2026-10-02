package io.crewscope.domain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeEntryVersionTest {

    private static final TeamScope SCOPE =
            new TeamScope(OrganizationId.generate(), TeamId.generate());
    private static final KnowledgeEntryId ENTRY_ID = KnowledgeEntryId.generate();
    private static final PrincipalId ACTOR = PrincipalId.generate();
    private static final UtcTimestamp T0 = UtcTimestamp.parse("2026-10-01T09:00:00Z");

    private static KnowledgeEntryVersion version(
            long revision, Optional<KnowledgeEntryRevision> previous, String title, String content) {
        return KnowledgeEntryVersion.create(
                ENTRY_ID, SCOPE, new KnowledgeEntryRevision(revision), previous,
                title, content, ACTOR, T0);
    }

    @Test
    void firstRevisionCarriesNoPredecessorAndLaterOnesChainImmediately() {
        KnowledgeEntryVersion v1 = version(1L, Optional.empty(), "Title", "Content");
        KnowledgeEntryVersion v2 = version(2L, Optional.of(new KnowledgeEntryRevision(1L)), "Title", "Content");
        KnowledgeEntryVersion v3 = version(3L, Optional.of(new KnowledgeEntryRevision(2L)), "Title", "Content");

        assertTrue(v1.previousRevision().isEmpty());
        assertEquals(new KnowledgeEntryRevision(1L), v2.previousRevision().orElseThrow());
        assertEquals(new KnowledgeEntryRevision(2L), v3.previousRevision().orElseThrow());
    }

    @Test
    void revisionChainViolationsAreRejected() {
        DomainValidationException firstWithPredecessor = assertThrows(
                DomainValidationException.class,
                () -> version(1L, Optional.of(new KnowledgeEntryRevision(1L)), "Title", "Content"));
        assertEquals(
                "knowledgeEntryVersion.previousRevision",
                firstWithPredecessor.error().details().get("field"));

        DomainValidationException laterWithoutPredecessor = assertThrows(
                DomainValidationException.class,
                () -> version(2L, Optional.empty(), "Title", "Content"));
        assertEquals(
                "knowledgeEntryVersion.previousRevision",
                laterWithoutPredecessor.error().details().get("field"));

        DomainValidationException skippedPredecessor = assertThrows(
                DomainValidationException.class,
                () -> version(3L, Optional.of(new KnowledgeEntryRevision(1L)), "Title", "Content"));
        assertEquals(
                "knowledgeEntryVersion.previousRevision",
                skippedPredecessor.error().details().get("field"));
    }

    @Test
    void hashIsContentAddressedAndExcludesTheRevision() {
        KnowledgeContentHash first =
                KnowledgeContentHash.of("Title", "Content");
        KnowledgeContentHash sameContent =
                KnowledgeContentHash.of("Title", "Content");
        KnowledgeContentHash otherContent =
                KnowledgeContentHash.of("Title", "Different content");

        assertEquals(first, sameContent);
        assertNotEquals(first, otherContent);

        KnowledgeEntryVersion v1 = version(1L, Optional.empty(), "Title", "Content");
        KnowledgeEntryVersion v5 = version(5L, Optional.of(new KnowledgeEntryRevision(4L)), "Title", "Content");
        assertEquals(v1.contentHash(), v5.contentHash());
    }

    @Test
    void reconstituteDetectsStoredHashThatNoLongerMatchesContent() {
        KnowledgeEntryVersion v1 = version(1L, Optional.empty(), "Title", "Content");

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> KnowledgeEntryVersion.reconstitute(
                        ENTRY_ID, SCOPE, v1.revision(), v1.previousRevision(),
                        v1.title(), "tampered content", v1.contentHash(),
                        AuditMetadata.createdBy(ACTOR, T0)));

        assertEquals("knowledgeEntryVersion.contentHash", failure.error().details().get("field"));
    }

    @Test
    void blankTitleAndOversizedContentAreRejected() {
        assertThrows(DomainValidationException.class,
                () -> version(1L, Optional.empty(), "  ", "Content"));
        assertThrows(DomainValidationException.class,
                () -> version(1L, Optional.empty(), "Title", " ".repeat(65537)));
    }
}
