package io.crewscope.application.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import org.junit.jupiter.api.Test;

/** Locks the exact reviewed event coordinates behind all five M6 Inbox source families. */
class InboxEventTypeRegistryM6E03Test {

    private final InboxEventTypeRegistry registry = CrewScopeInboxEventTypes.reviewedRegistry();

    @Test
    void coversOwnerExecutorReviewerConfirmerAndExceptionOperations() {
        assertOperation("WORK_ITEM_OWNER_ASSIGNED", InboxProjectionOperation.RESPONSIBILITY_ASSIGNED);
        assertOperation("WORK_ITEM_EXECUTOR_ASSIGNED", InboxProjectionOperation.RESPONSIBILITY_ASSIGNED);
        assertOperation("REVIEW_REQUEST_CREATED", InboxProjectionOperation.REVIEW_OPENED);
        assertOperation("ACTION_BUNDLE_PLANNED", InboxProjectionOperation.CONFIRMATION_OPENED);
        assertOperation("WORKER_TASK_FAIL_ACCEPTED", InboxProjectionOperation.TASK_EXCEPTION_OPENED);
        assertOperation("ACTION_DISPATCH_TRANSITIONED",
                InboxProjectionOperation.ACTION_DELIVERY_REFRESHED);
    }

    @Test
    void matchesSchemaCoordinatesExactlyAndReviewsTaskRetryV1AndV2() {
        assertEquals(16, registry.size());
        assertTrue(registry.find(
                EventType.from("MEMBER_TASK_RETRY_ACCEPTED"), SchemaVersion.V1).isPresent());
        assertTrue(registry.find(
                EventType.from("MEMBER_TASK_RETRY_ACCEPTED"), SchemaVersion.V2).isPresent());
        assertTrue(registry.find(
                EventType.from("WORK_ITEM_OWNER_ASSIGNED"), SchemaVersion.V2).isEmpty());
        assertTrue(registry.find(
                EventType.from("UNREVIEWED_PRIVATE_EVENT"), SchemaVersion.V1).isEmpty());
    }

    private void assertOperation(String eventType, InboxProjectionOperation operation) {
        assertEquals(
                operation,
                registry.find(EventType.from(eventType), SchemaVersion.V1)
                        .orElseThrow()
                        .operation());
    }
}
