package io.crewscope.application.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
            "leaseId", "attempt", "expiredPhase", "leaseExpiredAt",
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
    private static final Set<String> LIST_FIELDS = Set.of(
            "acceptanceCriteria", "providerBindingIds");

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
                publicValue(type, key, value).ifPresent(safe -> result.put(key, safe));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Optional<Object> publicValue(String eventType, String key, Object value) {
        if (value == null || isScalar(value)) {
            return Optional.ofNullable(value);
        }
        if (LIST_FIELDS.contains(key) && value instanceof List<?> list
                && list.stream().allMatch(TaskPublicEventMapper::isScalar)) {
            return Optional.of(List.copyOf(list));
        }
        if (eventType.equals("AGENT_RUN_EVENT_RECORDED") && value instanceof Map<?, ?> map) {
            if (key.equals("usage")) {
                return Optional.of(copyNested(map, USAGE_FIELDS));
            }
            if (key.equals("failure")) {
                return Optional.of(copyNested(map, FAILURE_FIELDS));
            }
        }
        // A corrupted or future payload cannot smuggle nested credential fields through a scalar
        // public field. Unsupported shapes are omitted from the projection.
        return Optional.empty();
    }

    private static Map<String, Object> copyNested(Map<?, ?> source, Set<String> fields) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String name && fields.contains(name) && isScalar(value)) {
                result.put(name, value);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
