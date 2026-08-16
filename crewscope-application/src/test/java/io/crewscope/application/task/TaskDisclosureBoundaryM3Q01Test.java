package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Type-confusion probes cannot move nested secrets through public Task Event fields. */
class TaskDisclosureBoundaryM3Q01Test {

    private final TaskPublicEventMapper mapper = new TaskPublicEventMapper();

    @Test
    void omitsNestedObjectsFromScalarAndListFields() {
        Map<String, Object> result = mapper.map(
                "AGENT_RUN_EVENT_RECORDED",
                Map.of(
                        "eventKind", "TEXT_DELTA",
                        "safeText", Map.of("taskToken", "secret"),
                        "usage", Map.of(
                                "totalTokens", 20,
                                "inputTokens", Map.of("credential", "secret")),
                        "failure", Map.of(
                                "safeMessage", "safe",
                                "runtimeCode", "CONTROLLED_FAILURE",
                                "rawProviderError", Map.of("apiKey", "secret"))));

        assertEquals("TEXT_DELTA", result.get("eventKind"));
        assertFalse(result.containsKey("safeText"));
        assertEquals(Map.of("totalTokens", 20), result.get("usage"));
        assertEquals(
                Map.of("safeMessage", "safe", "runtimeCode", "CONTROLLED_FAILURE"),
                result.get("failure"));
        assertFalse(result.toString().contains("secret"));
    }

    @Test
    void keepsOnlyScalarCollectionsForDelegationProjection() {
        Map<String, Object> result = mapper.map(
                "TASK_DELEGATED_TO_AGENT",
                Map.of(
                        "objective", "safe objective",
                        "acceptanceCriteria", List.of(Map.of("token", "secret")),
                        "providerBindingIds", List.of("binding-1", "binding-2")));

        assertEquals("safe objective", result.get("objective"));
        assertFalse(result.containsKey("acceptanceCriteria"));
        assertEquals(List.of("binding-1", "binding-2"), result.get("providerBindingIds"));
        assertFalse(result.toString().contains("secret"));
    }

    @Test
    void recoveryProjectionOmitsTheInternalFencingEpoch() {
        Map<String, Object> result = mapper.map(
                "TASK_EXECUTION_RECOVERY_STARTED",
                Map.of(
                        "leaseId", "lease-1",
                        "attempt", 2,
                        "fencingToken", 41,
                        "expiredPhase", "RUN"));

        assertEquals("lease-1", result.get("leaseId"));
        assertFalse(result.containsKey("fencingToken"));
    }
}
