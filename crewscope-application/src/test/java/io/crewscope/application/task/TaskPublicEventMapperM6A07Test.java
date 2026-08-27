package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fail-closed Task Timeline event-type and payload whitelist proof for M6-A07. */
class TaskPublicEventMapperM6A07Test {

    @Test
    void publishesOnlyRegisteredTypesAndNeverPassesUnknownNestedFields() {
        TaskPublicEventMapper mapper = new TaskPublicEventMapper();

        assertTrue(mapper.supports("TASK_DELEGATED_TO_AGENT"));
        assertFalse(mapper.supports("FUTURE_CREDENTIAL_EXPOSED"));
        assertThrows(IllegalStateException.class, () ->
                mapper.map("FUTURE_CREDENTIAL_EXPOSED", Map.of("credential", "secret")));

        Map<String, Object> safe = mapper.map(
                "TASK_DELEGATED_TO_AGENT",
                Map.of("taskId", "safe-id", "credential", "secret",
                        "objective", Map.of("authorization", "nested-secret")));
        assertTrue(safe.containsKey("taskId"));
        assertFalse(safe.containsKey("credential"));
        assertFalse(safe.containsKey("objective"));
    }
}
