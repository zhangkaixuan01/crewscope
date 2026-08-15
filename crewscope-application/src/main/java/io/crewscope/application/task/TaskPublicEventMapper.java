package io.crewscope.application.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit disclosure whitelist for durable Task Event payloads. */
public final class TaskPublicEventMapper {

    private static final Set<String> MEMBER_COMMAND_TYPES = Set.of(
            "MEMBER_TASK_PAUSE_ACCEPTED",
            "MEMBER_TASK_RESUME_ACCEPTED",
            "MEMBER_TASK_CANCEL_ACCEPTED",
            "MEMBER_TASK_RETRY_ACCEPTED");
    private static final Set<String> WORKER_COMMAND_TYPES = Set.of(
            "WORKER_TASK_PREPARE_ACCEPTED",
            "WORKER_TASK_START_ACCEPTED",
            "WORKER_TASK_HEARTBEAT_ACCEPTED",
            "WORKER_TASK_PROGRESS_ACCEPTED",
            "WORKER_TASK_COMPLETE_ACCEPTED",
            "WORKER_TASK_FAIL_ACCEPTED");
    private static final Set<String> DELEGATION_FIELDS = Set.of(
            "taskId", "taskExecutionId", "workItemId", "workItemVersion", "objective",
            "acceptanceCriteria", "briefHash", "sourceConversationId", "sourceMessageId",
            "sourceMessageSequence", "executorPrincipalId", "executorAssignmentId",
            "executorAssignmentVersion", "agentProfileId", "agentProfileVersion",
            "policySnapshotId", "policySnapshotHash", "safetyOverlayId",
            "safetyOverlayVersion", "providerBindingIds", "taskStatus", "executionStatus");
    private static final Set<String> MEMBER_COMMAND_FIELDS = Set.of(
            "taskId", "targetExecutionId", "targetAttempt", "operation", "taskStatus",
            "executionStatus", "successorExecutionId", "successorAttempt");
    private static final Set<String> WORKER_COMMAND_FIELDS = Set.of(
            "taskExecutionId", "attempt", "executionLeaseId", "operation",
            "taskExecutionVersion", "leaseVersion", "safeSummary", "progressPercent",
            "failureClass", "failureCode");
    private static final Set<String> RECOVERY_FIELDS = Set.of(
            "leaseId", "attempt", "fencingToken", "expiredPhase", "leaseExpiredAt",
            "recoveryStartedAt");
    private static final Set<String> RESUME_FIELDS = Set.of(
            "taskExecutionId", "agentRunId", "agentInterruptId",
            "resumedSegmentSequence", "resumeRequestId");
    private static final Set<String> AGENT_RUN_FIELDS = Set.of(
            "taskExecutionId", "attempt", "agentRunId", "segmentSequence", "eventSequence",
            "eventKind", "runtimeOccurredAt", "safeText", "name", "status", "referenceType",
            "referenceId", "contentHash", "succeeded", "progressPercent", "modelAttempt",
            "modelMaxAttempts", "usage", "failure");
    private static final Set<String> USAGE_FIELDS = Set.of(
            "inputTokens", "outputTokens", "cachedTokens", "totalTokens");
    private static final Set<String> FAILURE_FIELDS = Set.of(
            "category", "retryable", "safeMessage", "runtimeCode");

    /** Returns a new immutable map containing only fields approved for the public Task stream. */
    public Map<String, Object> map(String eventType, Map<String, Object> payload) {
        String type = Objects.requireNonNull(eventType, "eventType");
        Map<String, Object> source = Objects.requireNonNull(payload, "payload");
        Set<String> fields;
        if (type.equals("TASK_DELEGATED_TO_AGENT")) {
            fields = DELEGATION_FIELDS;
        } else if (MEMBER_COMMAND_TYPES.contains(type)) {
            fields = MEMBER_COMMAND_FIELDS;
        } else if (WORKER_COMMAND_TYPES.contains(type)) {
            fields = WORKER_COMMAND_FIELDS;
        } else if (type.equals("TASK_EXECUTION_RECOVERY_STARTED")) {
            fields = RECOVERY_FIELDS;
        } else if (type.equals("AGENT_RUN_RESUMED")) {
            fields = RESUME_FIELDS;
        } else if (type.equals("AGENT_RUN_EVENT_RECORDED")) {
            fields = AGENT_RUN_FIELDS;
        } else {
            throw new IllegalStateException("Task Event type is not publicly mapped: " + type);
        }

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (fields.contains(key)) {
                result.put(key, nested(type, key, value));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Object nested(String eventType, String key, Object value) {
        if (!eventType.equals("AGENT_RUN_EVENT_RECORDED") || !(value instanceof Map<?, ?> map)) {
            return value;
        }
        if (key.equals("usage")) {
            return copyNested(map, USAGE_FIELDS);
        }
        if (key.equals("failure")) {
            return copyNested(map, FAILURE_FIELDS);
        }
        return value;
    }

    private static Map<String, Object> copyNested(Map<?, ?> source, Set<String> fields) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String name && fields.contains(name)) {
                result.put(name, value);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}
