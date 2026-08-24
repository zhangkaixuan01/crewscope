package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proves that Task Event payload disclosure remains an explicit fail-closed whitelist. */
class TaskPublicEventMapperM3A05Test {

    private final TaskPublicEventMapper mapper = new TaskPublicEventMapper();

    @Test
    void removesInternalTokenReasoningToolArgumentsAndUnknownNestedFields() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskExecutionId", "execution");
        payload.put("eventKind", "MODEL_COMPLETED");
        payload.put("safeText", "public summary");
        payload.put("interruptToken", "secret");
        payload.put("reasoning", "private chain");
        payload.put("toolArguments", Map.of("credential", "secret"));
        payload.put("usage", Map.of(
                "inputTokens", 10,
                "outputTokens", 5,
                "cachedTokens", 2,
                "totalTokens", 15,
                "providerRequest", "private"));
        payload.put("failure", Map.of(
                "category", "PROVIDER",
                "retryable", true,
                "safeMessage", "temporarily unavailable",
                "runtimeCode", "MODEL_UNAVAILABLE",
                "rawProviderError", "private"));

        Map<String, Object> result = mapper.map("AGENT_RUN_EVENT_RECORDED", payload);

        assertEquals("public summary", result.get("safeText"));
        assertFalse(result.containsKey("interruptToken"));
        assertFalse(result.containsKey("reasoning"));
        assertFalse(result.containsKey("toolArguments"));
        assertFalse(((Map<?, ?>) result.get("usage")).containsKey("providerRequest"));
        assertFalse(((Map<?, ?>) result.get("failure")).containsKey("rawProviderError"));
    }

    @Test
    void rejectsUnknownTaskEventTypesInsteadOfAccidentallyPublishingTheirPayload() {
        assertThrows(
                IllegalStateException.class,
                () -> mapper.map("FUTURE_INTERNAL_TASK_EVENT", Map.of("token", "secret")));
        assertThrows(
                IllegalStateException.class,
                () -> mapper.map(
                        "WORKER_TASK_FUTURE_ACCEPTED", Map.of("safeSummary", "unreviewed")));
    }

    @Test
    void publishesDiffMetadataButNeverPatchContentOrInternalWorkspaceCoordinates() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", "src/main/java/App.java");
        file.put("changeType", "MODIFIED");
        file.put("additions", 4);
        file.put("deletions", 1);
        file.put("binary", false);
        file.put("patchTruncated", true);
        file.put("patchSha256", "a".repeat(64));
        file.put("patchPreview", "secret source content");
        file.put("canonicalPath", "/private/worktree/App.java");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", "workspace");
        payload.put("taskExecutionId", "execution");
        payload.put("attempt", 1);
        payload.put("streamEpoch", "epoch");
        payload.put("sequence", 2);
        payload.put("diffGeneration", 2);
        payload.put("changeKind", "DELTA");
        payload.put("manifestHash", "b".repeat(64));
        payload.put("upserts", List.of(file));
        payload.put("removals", List.of("old.txt"));
        payload.put("containerId", "secret");
        Map<String, Object> result = mapper.map("WORKSPACE_DIFF_DELTA", payload);

        Map<?, ?> publishedFile = (Map<?, ?>) ((List<?>) result.get("upserts")).get(0);
        assertEquals("src/main/java/App.java", publishedFile.get("path"));
        assertFalse(publishedFile.containsKey("patchPreview"));
        assertFalse(publishedFile.containsKey("canonicalPath"));
        assertFalse(result.containsKey("containerId"));
    }

    @Test
    void rejectsMalformedNestedDiffCollectionsInsteadOfPartiallyTrustingThem() {
        Map<String, Object> result = mapper.map(
                "WORKSPACE_DIFF_RESET",
                Map.of("upserts", List.of("not-a-file"), "removals", List.of("safe.txt")));

        assertFalse(result.containsKey("upserts"));
        assertEquals(List.of("safe.txt"), result.get("removals"));
    }

    @Test
    void publishesReviewAndActionFactsWithoutInternalAuthorityOrWorkerCoordinates() {
        Map<String, Object> review = mapper.map(
                "REVIEW_DECISION_RECORDED",
                Map.of(
                        "decisionId", "decision",
                        "decisionType", "APPROVED",
                        "eligibilityMode", "INDEPENDENT_MEMBER",
                        "decisionHash", "internal-authority-hash",
                        "overrideReason", "private"));
        assertEquals("APPROVED", review.get("decisionType"));
        assertFalse(review.containsKey("decisionHash"));
        assertFalse(review.containsKey("overrideReason"));

        Map<String, Object> action = mapper.map(
                "ACTION_DISPATCH_TRANSITIONED",
                Map.of(
                        "actionBundleId", "bundle",
                        "plannedActionId", "action",
                        "status", "UNKNOWN",
                        "claimAttempts", 1,
                        "reconciliationAttempts", 2,
                        "fencingToken", 99,
                        "claimWorkerId", "worker-secret",
                        "idempotencyKey", "internal-key"));
        assertEquals("UNKNOWN", action.get("status"));
        assertFalse(action.containsKey("fencingToken"));
        assertFalse(action.containsKey("claimWorkerId"));
        assertFalse(action.containsKey("idempotencyKey"));
    }

    @Test
    void publishesOnlyHashedExternalIdentityForGithubResultTimelineFacts() {
        Map<String, Object> result = mapper.map(
                "EXTERNAL_RESULT_MERGED",
                Map.of(
                        "externalObjectType", "PULL_REQUEST",
                        "providerStatus", "OPEN",
                        "externalIdentityHash", "a".repeat(64),
                        "externalId", "123",
                        "externalBusinessKey", "owner/repo#123",
                        "connectionId", "secret-connection",
                        "lastObservationKey", "private"));

        assertEquals("OPEN", result.get("providerStatus"));
        assertEquals("a".repeat(64), result.get("externalIdentityHash"));
        assertFalse(result.containsKey("externalId"));
        assertFalse(result.containsKey("externalBusinessKey"));
        assertFalse(result.containsKey("connectionId"));
        assertFalse(result.containsKey("lastObservationKey"));
    }
}
