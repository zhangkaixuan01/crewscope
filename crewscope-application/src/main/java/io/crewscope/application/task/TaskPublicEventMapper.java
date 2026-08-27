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
            "safetyOverlayVersion", "providerBindingIds", "taskStatus", "executionStatus",
            "agentExecutionScope", "agentConfigurationRevision", "agentModelBindingSource");
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
    private static final Set<String> WORKSPACE_FIELDS = Set.of(
            "workspaceId", "taskExecutionId", "attempt", "status", "recoveryTargetStatus",
            "recoveryGeneration", "completionReason", "failureCode", "workspaceVersion");
    private static final Set<String> DIFF_FIELDS = Set.of(
            "workspaceId", "taskExecutionId", "attempt", "streamEpoch", "sequence",
            "diffGeneration", "changeKind", "manifestHash", "upserts", "removals");
    private static final Set<String> DIFF_FILE_FIELDS = Set.of(
            "path", "oldPath", "changeType", "additions", "deletions", "binary",
            "patchTruncated", "patchSha256");
    private static final Set<String> TEST_EVIDENCE_FIELDS = Set.of(
            "testEvidenceId", "workspaceId", "taskExecutionId", "attempt",
            "evidenceSequence", "diffGeneration", "manifestHash", "succeeded", "total",
            "passed", "failed", "errors", "skipped", "acceptancePassed",
            "acceptanceFailed", "acceptanceNotEvaluated", "evidenceHash");
    private static final Set<String> FINAL_DIFF_FIELDS = Set.of(
            "diffArtifactId", "workspaceId", "taskExecutionId", "attempt",
            "diffGeneration", "manifestHash", "fileCount", "additions", "deletions",
            "finalHash");
    private static final Set<String> REVIEW_REQUEST_FIELDS = Set.of(
            "reviewRequestId", "taskId", "taskExecutionId", "attempt", "requestRevision",
            "requestVersion", "aggregateVersion", "status", "reason");
    private static final Set<String> REVIEW_FINDING_FIELDS = Set.of(
            "findingId", "taskId", "taskExecutionId", "attempt", "reviewRequestId",
            "reviewRequestRevision", "severity", "category", "reviewerRelationship");
    private static final Set<String> REVIEW_DUPLICATE_FIELDS = Set.of(
            "observationId", "findingId", "reviewRequestId", "observationNumber");
    private static final Set<String> REVIEW_DECISION_FIELDS = Set.of(
            "decisionId", "taskId", "reviewRequestId", "reviewRequestRevision",
            "decisionRevision", "reviewerPrincipalId", "reviewerMemberId",
            "eligibilityMode", "decisionType");
    private static final Set<String> REVIEW_ROUND_FIELDS = Set.of(
            "roundId", "taskId", "roundNumber", "sourceReviewRequestId",
            "sourceReviewRequestRevision", "triggerDecisionId");
    private static final Set<String> ACTION_BUNDLE_FIELDS = Set.of(
            "actionBundleId", "taskId", "taskExecutionId", "reviewDecisionId",
            "bundleDigest", "actionKinds", "actionDigests", "validUntil");
    private static final Set<String> ACTION_CONFIRMATION_FIELDS = Set.of(
            "confirmationId", "actionBundleId", "bundleDigest", "actionDigests",
            "confirmedByPrincipalId", "validUntil", "cancellationReason",
            "confirmationVersion");
    private static final Set<String> ACTION_DISPATCH_FIELDS = Set.of(
            "actionDispatchId", "actionBundleId", "plannedActionId", "actionDigest",
            "status", "claimAttempts", "reconciliationAttempts", "dispatchVersion");
    private static final Set<String> ACTION_RECEIPT_FIELDS = Set.of(
            "actionReceiptId", "actionBundleId", "plannedActionId", "actionDigest",
            "result", "source", "externalIdentityHash", "evidenceCode",
            "resolvedByPrincipalId");
    private static final Set<String> EXTERNAL_RESULT_FIELDS = Set.of(
            "externalResultId", "plannedActionId", "externalIdentityHash",
            "externalObjectType", "providerStatus", "providerVersion", "source",
            "mergeOutcome", "resultVersion");
    private static final Set<String> LIST_FIELDS = Set.of(
            "acceptanceCriteria", "providerBindingIds", "actionKinds", "actionDigests");

    /** Returns a new immutable map containing only fields approved for the public Task stream. */
    public Map<String, Object> map(String eventType, Map<String, Object> payload) {
        String type = Objects.requireNonNull(eventType, "eventType");
        Map<String, Object> source = Objects.requireNonNull(payload, "payload");
        Set<String> fields = fieldsFor(type).orElseThrow(() ->
                new IllegalStateException("Task Event type is not publicly mapped: " + type));

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (fields.contains(key)) {
                publicValue(type, key, value).ifPresent(safe -> result.put(key, safe));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /** Allows persistence readers to skip future event types before they enter a public page. */
    public boolean supports(String eventType) {
        return fieldsFor(Objects.requireNonNull(eventType, "eventType")).isPresent();
    }

    /** Returns the frozen event-type whitelist used by SQL readers and contract tests. */
    public Set<String> publicEventTypes() {
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        types.add("TASK_DELEGATED_TO_AGENT");
        types.addAll(MEMBER_COMMAND_TYPES);
        types.addAll(WORKER_COMMAND_TYPES);
        types.addAll(Set.of(
                "TASK_EXECUTION_RECOVERY_STARTED", "AGENT_RUN_RESUMED",
                "AGENT_RUN_EVENT_RECORDED", "EXECUTION_WORKSPACE_CHANGED",
                "WORKSPACE_DIFF_RESET", "WORKSPACE_DIFF_DELTA",
                "TEST_EVIDENCE_PUBLISHED", "FINAL_DIFF_ARTIFACT_PUBLISHED",
                "REVIEW_REQUEST_CREATED", "REVIEW_REQUEST_STARTED",
                "REVIEW_REQUEST_COMPLETED", "REVIEW_REQUEST_INVALIDATED",
                "REVIEW_FINDING_RECORDED", "REVIEW_FINDING_DUPLICATE_OBSERVED",
                "REVIEW_DECISION_RECORDED", "REVIEW_MODIFICATION_ROUND_STARTED",
                "ACTION_BUNDLE_PLANNED", "ACTION_BUNDLE_CONFIRMED",
                "ACTION_CONFIRMATION_CANCELLED", "ACTION_DISPATCH_TRANSITIONED",
                "ACTION_RECEIPT_RECORDED", "EXTERNAL_RESULT_MERGED"));
        return Collections.unmodifiableSet(types);
    }

    private static Optional<Set<String>> fieldsFor(String type) {
        if (type.equals("TASK_DELEGATED_TO_AGENT")) {
            return Optional.of(DELEGATION_FIELDS);
        } else if (MEMBER_COMMAND_TYPES.contains(type)) {
            return Optional.of(MEMBER_COMMAND_FIELDS);
        } else if (WORKER_COMMAND_TYPES.contains(type)) {
            return Optional.of(WORKER_COMMAND_FIELDS);
        } else if (type.equals("TASK_EXECUTION_RECOVERY_STARTED")) {
            return Optional.of(RECOVERY_FIELDS);
        } else if (type.equals("AGENT_RUN_RESUMED")) {
            return Optional.of(RESUME_FIELDS);
        } else if (type.equals("AGENT_RUN_EVENT_RECORDED")) {
            return Optional.of(AGENT_RUN_FIELDS);
        } else if (type.equals("EXECUTION_WORKSPACE_CHANGED")) {
            return Optional.of(WORKSPACE_FIELDS);
        } else if (type.equals("WORKSPACE_DIFF_RESET")
                || type.equals("WORKSPACE_DIFF_DELTA")) {
            return Optional.of(DIFF_FIELDS);
        } else if (type.equals("TEST_EVIDENCE_PUBLISHED")) {
            return Optional.of(TEST_EVIDENCE_FIELDS);
        } else if (type.equals("FINAL_DIFF_ARTIFACT_PUBLISHED")) {
            return Optional.of(FINAL_DIFF_FIELDS);
        } else if (type.equals("REVIEW_REQUEST_CREATED")
                || type.equals("REVIEW_REQUEST_STARTED")
                || type.equals("REVIEW_REQUEST_COMPLETED")
                || type.equals("REVIEW_REQUEST_INVALIDATED")) {
            return Optional.of(REVIEW_REQUEST_FIELDS);
        } else if (type.equals("REVIEW_FINDING_RECORDED")) {
            return Optional.of(REVIEW_FINDING_FIELDS);
        } else if (type.equals("REVIEW_FINDING_DUPLICATE_OBSERVED")) {
            return Optional.of(REVIEW_DUPLICATE_FIELDS);
        } else if (type.equals("REVIEW_DECISION_RECORDED")) {
            return Optional.of(REVIEW_DECISION_FIELDS);
        } else if (type.equals("REVIEW_MODIFICATION_ROUND_STARTED")) {
            return Optional.of(REVIEW_ROUND_FIELDS);
        } else if (type.equals("ACTION_BUNDLE_PLANNED")) {
            return Optional.of(ACTION_BUNDLE_FIELDS);
        } else if (type.equals("ACTION_BUNDLE_CONFIRMED")
                || type.equals("ACTION_CONFIRMATION_CANCELLED")) {
            return Optional.of(ACTION_CONFIRMATION_FIELDS);
        } else if (type.equals("ACTION_DISPATCH_TRANSITIONED")) {
            return Optional.of(ACTION_DISPATCH_FIELDS);
        } else if (type.equals("ACTION_RECEIPT_RECORDED")) {
            return Optional.of(ACTION_RECEIPT_FIELDS);
        } else if (type.equals("EXTERNAL_RESULT_MERGED")) {
            return Optional.of(EXTERNAL_RESULT_FIELDS);
        }
        return Optional.empty();
    }

    private static Optional<Object> publicValue(String eventType, String key, Object value) {
        if (value == null || isScalar(value)) {
            return Optional.ofNullable(value);
        }
        if (LIST_FIELDS.contains(key) && value instanceof List<?> list
                && list.stream().allMatch(TaskPublicEventMapper::isScalar)) {
            return Optional.of(List.copyOf(list));
        }
        if (eventType.startsWith("WORKSPACE_DIFF_") && key.equals("removals")
                && value instanceof List<?> list
                && list.stream().allMatch(TaskPublicEventMapper::isScalar)) {
            return Optional.of(List.copyOf(list));
        }
        if (eventType.startsWith("WORKSPACE_DIFF_") && key.equals("upserts")
                && value instanceof List<?> list) {
            return copyNestedList(list, DIFF_FILE_FIELDS);
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

    private static Optional<Object> copyNestedList(List<?> source, Set<String> fields) {
        if (source.size() > 200 || source.stream().anyMatch(value -> !(value instanceof Map<?, ?>))) {
            return Optional.empty();
        }
        return Optional.of(source.stream()
                .map(value -> copyNested((Map<?, ?>) value, fields))
                .toList());
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }
}
