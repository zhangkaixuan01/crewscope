package io.crewscope.application.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Security contract for the exact-version M0-M5 public Activity EventType registry. */
class ActivityEventTypeRegistryM6E02Test {

    @Test
    void reviewedRegistryCoversEveryPublishedActivityCategory() {
        ActivityEventTypeRegistry registry = CrewScopeActivityEventTypes.reviewedRegistry();

        assertEquals(40, registry.definitions().size());
        assertTrue(registry.find(EventType.from("TEAM_CREATED"), SchemaVersion.V1).isPresent());
        assertTrue(registry.find(EventType.from("WORK_ITEM_CREATED"), SchemaVersion.V1).isPresent());
        assertTrue(registry.find(EventType.from("TASK_DELEGATED_TO_AGENT"), SchemaVersion.V1)
                .isPresent());
        assertTrue(registry.find(EventType.from("TASK_DELEGATED_TO_AGENT"), SchemaVersion.V2)
                .isPresent());
        assertTrue(registry.find(EventType.from("MEMBER_TASK_RETRY_ACCEPTED"), SchemaVersion.V2)
                .isPresent());
        assertTrue(registry.find(EventType.from("REVIEW_DECISION_RECORDED"), SchemaVersion.V1)
                .isPresent());
        assertTrue(registry.find(EventType.from("ACTION_RECEIPT_RECORDED"), SchemaVersion.V1)
                .isPresent());
        assertTrue(registry.find(EventType.from("GITHUB_PROVIDER_BOUND"), SchemaVersion.V1)
                .isPresent());
    }

    @Test
    void unknownTypeAndUnreviewedSchemaFailClosed() {
        ActivityEventTypeRegistry registry = CrewScopeActivityEventTypes.reviewedRegistry();

        assertFalse(registry.find(EventType.from("FUTURE_PRIVATE_EVENT"), SchemaVersion.V1)
                .isPresent());
        assertFalse(registry.find(EventType.from("WORK_ITEM_CREATED"), SchemaVersion.V2)
                .isPresent());
    }

    @Test
    void duplicateTypeAndSchemaCannotOverrideReviewedMapping() {
        ActivityEventTypeDefinition definition = CrewScopeActivityEventTypes.reviewedRegistry()
                .find(EventType.from("TEAM_CREATED"), SchemaVersion.V1)
                .orElseThrow();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ActivityEventTypeRegistry(List.of(definition, definition)));
    }

    @Test
    void publicFieldsExactlyMatchSchemaAndExcludeRawSources() {
        ActivityEventTypeDefinition definition = CrewScopeActivityEventTypes.reviewedRegistry()
                .find(EventType.from("WORK_ITEM_COMMENT_ADDED"), SchemaVersion.V1)
                .orElseThrow();

        assertEquals(java.util.Set.of("source"), definition.payloadSchema().allowedFields());
        assertFalse(definition.payloadSchema().allowedFields().contains("content"));
        assertFalse(definition.payloadSchema().allowedFields().contains("rawPayload"));
    }
}
