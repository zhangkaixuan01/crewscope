package io.crewscope.application.inbox;

import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.shared.event.SchemaVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reviewed Inbox event coordinates for the M6 member task matrix. */
public final class CrewScopeInboxEventTypes {

    private CrewScopeInboxEventTypes() {}

    public static InboxEventTypeRegistry reviewedRegistry() {
        List<InboxEventTypeDefinition> definitions = new ArrayList<>();
        add(definitions, "WORK_ITEM_OWNER_ASSIGNED",
                InboxProjectionOperation.RESPONSIBILITY_ASSIGNED, "role", "actorPrincipalId");
        add(definitions, "WORK_ITEM_OWNER_REPLACED",
                InboxProjectionOperation.RESPONSIBILITY_ASSIGNED,
                "role", "actorPrincipalId", "replacedAssignmentId");
        add(definitions, "WORK_ITEM_EXECUTOR_ASSIGNED",
                InboxProjectionOperation.RESPONSIBILITY_ASSIGNED, "role", "actorPrincipalId");
        add(definitions, "WORK_ITEM_RESPONSIBILITY_RELEASED",
                InboxProjectionOperation.RESPONSIBILITY_RELEASED, "role", "actorPrincipalId");

        add(definitions, "REVIEW_REQUEST_CREATED",
                InboxProjectionOperation.REVIEW_OPENED,
                "reviewRequestId", "requestRevision");
        add(definitions, "REVIEW_REQUEST_STARTED",
                InboxProjectionOperation.REVIEW_OPENED, "reviewRequestId");
        add(definitions, "REVIEW_REQUEST_COMPLETED",
                InboxProjectionOperation.REVIEW_COMPLETED, "reviewRequestId");
        add(definitions, "REVIEW_REQUEST_INVALIDATED",
                InboxProjectionOperation.REVIEW_SUPERSEDED,
                "reviewRequestId", "reviewRequestRevision");

        add(definitions, "ACTION_BUNDLE_PLANNED",
                InboxProjectionOperation.CONFIRMATION_OPENED,
                "actionBundleId", "validUntil");
        add(definitions, "ACTION_BUNDLE_CONFIRMED",
                InboxProjectionOperation.CONFIRMATION_COMPLETED, "actionBundleId");
        add(definitions, "ACTION_CONFIRMATION_CANCELLED",
                InboxProjectionOperation.CONFIRMATION_CANCELLED, "actionBundleId");

        add(definitions, "WORKER_TASK_FAIL_ACCEPTED",
                InboxProjectionOperation.TASK_EXCEPTION_OPENED,
                "taskExecutionId", "attempt", "operation");
        add(definitions, "MEMBER_TASK_RETRY_ACCEPTED", SchemaVersion.V1,
                InboxProjectionOperation.TASK_EXCEPTION_RESOLVED,
                "targetExecutionId", "targetAttempt", "operation");
        add(definitions, "MEMBER_TASK_RETRY_ACCEPTED", SchemaVersion.V2,
                InboxProjectionOperation.TASK_EXCEPTION_RESOLVED,
                "targetExecutionId", "targetAttempt", "operation");

        add(definitions, "ACTION_DISPATCH_TRANSITIONED",
                InboxProjectionOperation.ACTION_DELIVERY_REFRESHED,
                "plannedActionId", "status", "dispatchVersion");
        add(definitions, "ACTION_RECEIPT_RECORDED",
                InboxProjectionOperation.ACTION_DELIVERY_REFRESHED,
                "plannedActionId", "result");
        return new InboxEventTypeRegistry(definitions);
    }

    private static void add(
            List<InboxEventTypeDefinition> target,
            String eventType,
            InboxProjectionOperation operation,
            String... requiredFields) {
        add(target, eventType, SchemaVersion.V1, operation, requiredFields);
    }

    private static void add(
            List<InboxEventTypeDefinition> target,
            String eventType,
            SchemaVersion schemaVersion,
            InboxProjectionOperation operation,
            String... requiredFields) {
        target.add(new InboxEventTypeDefinition(
                EventType.from(eventType), schemaVersion, operation, Set.of(requiredFields)));
    }
}
