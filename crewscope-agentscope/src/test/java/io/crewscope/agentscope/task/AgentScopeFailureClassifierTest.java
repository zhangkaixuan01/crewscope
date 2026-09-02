package io.crewscope.agentscope.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.crewscope.application.execution.ExecutionFailureCategory;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** Contract tests for stable AgentScope failure classification and retry semantics. */
class AgentScopeFailureClassifierTest {

    @Test
    void classifiesTimeoutAndUnsafeEventWithoutProviderPayloads() {
        var timeout = AgentScopeFailureClassifier.classify(new TimeoutException());
        assertEquals(ExecutionFailureCategory.TIMEOUT, timeout.category());
        assertEquals("DURATION_BUDGET_EXCEEDED", timeout.runtimeCode().orElseThrow());

        var unsafe = AgentScopeFailureClassifier.classify(new IllegalArgumentException("secret prompt"));
        assertEquals(ExecutionFailureCategory.AUTHORIZATION, unsafe.category());
        assertEquals("TASK_RUNTIME_EVENT_REJECTED", unsafe.runtimeCode().orElseThrow());
        assertEquals("The Task runtime rejected an unsafe AgentScope event.", unsafe.safeMessage());
    }
}
