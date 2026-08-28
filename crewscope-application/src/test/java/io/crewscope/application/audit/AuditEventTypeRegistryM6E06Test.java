package io.crewscope.application.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.audit.AuditEventCategory;
import io.crewscope.domain.audit.AuditOutcome;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M6-E06 Audit baseline with later reviewed coordinates and safe-summary regressions. */
class AuditEventTypeRegistryM6E06Test {

    private final AuditEventTypeRegistry registry = CrewScopeAuditEventTypes.reviewedRegistry();

    @Test
    void registersTheReviewedM3ToM7Coordinates() {
        assertEquals(110, registry.size());
        assertCategory("WORK_ITEM_CREATED", SchemaVersion.V1, AuditEventCategory.WORK);
        assertCategory("TASK_DELEGATED_TO_AGENT", SchemaVersion.V2, AuditEventCategory.EXECUTION);
        assertCategory("AGENT_PROFILE_CREATED", SchemaVersion.V1, AuditEventCategory.AGENT);
        assertCategory("MODEL_CONNECTION_VERIFIED", SchemaVersion.V1, AuditEventCategory.MODEL);
        assertCategory("REVIEW_DECISION_RECORDED", SchemaVersion.V1, AuditEventCategory.REVIEW);
        assertCategory("ACTION_BUNDLE_CONFIRMED", SchemaVersion.V1, AuditEventCategory.ACTION);
        assertCategory(
                "PROJECTION_GENERATION_SWITCHED",
                SchemaVersion.V1,
                AuditEventCategory.PROJECTION);
        assertCategory(
                "NOTIFICATION_DELIVERY_FAILED",
                SchemaVersion.V1,
                AuditEventCategory.NOTIFICATION);
        assertCategory(
                "LARK_MEMBER_MAPPING_CONFIRMED",
                SchemaVersion.V1,
                AuditEventCategory.SECURITY);
        assertCategory(
                "OUTBOX_DEAD_LETTER_REPLAY_REQUESTED",
                SchemaVersion.V1,
                AuditEventCategory.SYSTEM);
        assertCategory(
                "PROJECTION_DEAD_LETTER_REPLAY_REQUESTED",
                SchemaVersion.V1,
                AuditEventCategory.PROJECTION);
        assertCategory(
                "AUDIT_EXPLORER_QUERIED",
                SchemaVersion.V1,
                AuditEventCategory.SECURITY);
        assertCategory(
                "AUDIT_EXPORT_GENERATED",
                SchemaVersion.V1,
                AuditEventCategory.SECURITY);
    }

    @Test
    void keepsLegacyAndFutureCoordinatesUnregistered() {
        assertTrue(registry.find(
                        EventType.from("LEGACY_UNKNOWN_EVENT"), SchemaVersion.V1)
                .isEmpty());
        assertTrue(registry.find(
                        EventType.from("AGENT_PROFILE_CREATED"), SchemaVersion.V2)
                .isEmpty());
        assertTrue(registry.find(
                        EventType.from("FUTURE_SECURITY_EVENT"), SchemaVersion.V1)
                .isEmpty());
    }

    @Test
    void projectsOnlyTheReviewedLowCardinalitySummary() {
        AuditEventTypeDefinition definition = definition("AGENT_RUN_EVENT_RECORDED");

        var summary = definition.projectSummary(Map.of(
                "eventKind", "RUN_ERROR",
                "attempt", "1",
                "segmentSequence", "2",
                "eventSequence", "7",
                "failureCategory", "MODEL_EXECUTION_FAILED",
                "retryable", "true",
                "runtimeCode", "MODEL_RATE_LIMITED"));

        assertEquals(AuditEventCategory.EXECUTION, summary.category());
        assertEquals("MODEL_RATE_LIMITED", summary.values().get("runtimeCode"));
        assertFalse(summary.values().containsKey("safeText"));
        assertEquals(
                AuditOutcome.FAILED,
                definition.resolveOutcome(Optional.of("RUN_ERROR")));
        assertTrue(definition("TEAM_CREATED").projectSummary(Map.of()).values().isEmpty());
    }

    @Test
    void rejectsUnknownSummaryFieldsAndSecretOrPiiValues() {
        AuditEventTypeDefinition definition = definition("MODEL_CONNECTION_VERIFIED");
        Map<String, String> required = Map.of(
                "operation", "VERIFIED",
                "providerKey", "deepseek",
                "connectionStatus", "ACTIVE");

        assertEquals(3, definition.projectSummary(required).values().size());
        assertThrows(
                DomainValidationException.class,
                () -> definition.projectSummary(Map.of(
                        "operation", "VERIFIED",
                        "providerKey", "deepseek",
                        "connectionStatus", "ACTIVE",
                        "rawPayload", "private")));
        assertThrows(
                DomainValidationException.class,
                () -> definition.projectSummary(Map.of(
                        "operation", "VERIFIED",
                        "providerKey", "Authorization: Bearer secret",
                        "connectionStatus", "ACTIVE")));
        assertThrows(
                DomainValidationException.class,
                () -> definition.projectSummary(Map.of(
                        "operation", "VERIFIED",
                        "providerKey", "admin@example.com",
                        "connectionStatus", "ACTIVE")));
    }

    private void assertCategory(
            String eventType, SchemaVersion version, AuditEventCategory category) {
        assertEquals(category, registry.find(EventType.from(eventType), version)
                .orElseThrow()
                .category());
    }

    private AuditEventTypeDefinition definition(String eventType) {
        return registry.find(EventType.from(eventType), SchemaVersion.V1).orElseThrow();
    }
}
