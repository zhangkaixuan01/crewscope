package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
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
}
