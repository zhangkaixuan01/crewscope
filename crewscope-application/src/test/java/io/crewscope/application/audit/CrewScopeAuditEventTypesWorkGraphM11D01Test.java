package io.crewscope.application.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.audit.AuditRetentionLevel;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M11-D01: WorkItemRelation lifecycle events carry reviewed audit coordinates. */
class CrewScopeAuditEventTypesWorkGraphM11D01Test {

    private final AuditEventTypeRegistry registry = CrewScopeAuditEventTypes.reviewedRegistry();

    @Test
    void registersBothRelationEventTypesInTheWorkCategory() {
        AuditEventTypeDefinition created =
                definition("WORK_ITEM_RELATION_CREATED");
        assertEquals(AuditEventCategory.WORK, created.category());
        assertEquals(AuditRetentionLevel.STANDARD, created.retentionLevel());
        assertEquals(AuditOutcome.SUCCEEDED, created.resolveOutcome(Optional.empty()));

        AuditEventTypeDefinition removed =
                definition("WORK_ITEM_RELATION_REMOVED");
        assertEquals(AuditEventCategory.WORK, removed.category());
        assertEquals(AuditRetentionLevel.STANDARD, removed.retentionLevel());
        assertEquals(AuditOutcome.SUCCEEDED, removed.resolveOutcome(Optional.empty()));
    }

    @Test
    void projectsTheStoredDirectionAndRejectsAnythingElse() {
        AuditEventTypeDefinition created = definition("WORK_ITEM_RELATION_CREATED");
        // every segment carries a letter: an all-digit UUID segment trips the AuditSummarySchema
        // phone heuristic, so deterministic ids keep this projection test stable
        var summary =
                created.projectSummary(
                        Map.of(
                                "kind", "BLOCKS",
                                "srcWorkItemId", "aaaaaaa1-bbbb-4ccc-8ddd-eeeeeeeef001",
                                "dstWorkItemId", "bbbbbbb2-cccc-4ddd-8eee-fffffffff002"));
        assertEquals(3, summary.values().size());
        assertEquals("BLOCKS", summary.values().get("kind"));

        assertThrows(
                DomainValidationException.class,
                () -> created.projectSummary(Map.of("kind", "BLOCKS")));
        assertThrows(
                DomainValidationException.class,
                () -> created.projectSummary(
                        Map.of(
                                "kind", "BLOCKS",
                                "srcWorkItemId", "aaaaaaa1-bbbb-4ccc-8ddd-eeeeeeeef001",
                                "dstWorkItemId", "bbbbbbb2-cccc-4ddd-8eee-fffffffff002",
                                "relationId", "ccccccc3-dddd-4eee-8fff-999999999003")));
    }

    private AuditEventTypeDefinition definition(String eventType) {
        return registry.find(EventType.from(eventType), SchemaVersion.V1).orElseThrow();
    }
}
