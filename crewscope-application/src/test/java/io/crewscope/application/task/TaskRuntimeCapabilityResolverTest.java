package io.crewscope.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.domain.task.ExecutionCapability;
import io.crewscope.domain.task.PolicySnapshot;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Keeps the Policy-to-Runtime routing vocabulary explicit and exhaustive. */
class TaskRuntimeCapabilityResolverTest {

    @Test
    void mapsEveryRoutedExecutionCapabilityAndLeavesContextAccountingLocal() {
        PolicySnapshot policy = mock(PolicySnapshot.class);
        when(policy.capabilities()).thenReturn(EnumSet.allOf(ExecutionCapability.class));

        RuntimeCapabilities resolved = TaskRuntimeCapabilityResolver.resolve(policy);

        assertEquals(
                Set.of(
                        RuntimeCapability.TASK_EXECUTION,
                        RuntimeCapability.STREAMING,
                        RuntimeCapability.DURABLE_EVENT_STREAM,
                        RuntimeCapability.INTERRUPT_RESUME,
                        RuntimeCapability.PAUSE_RESUME,
                        RuntimeCapability.CANCEL,
                        RuntimeCapability.SESSION_STATE,
                        RuntimeCapability.PLAN,
                        RuntimeCapability.STRUCTURED_OUTPUT,
                        RuntimeCapability.EXTERNAL_TOOL,
                        RuntimeCapability.SANDBOX,
                        RuntimeCapability.WORKTREE,
                        RuntimeCapability.MULTI_REPOSITORY),
                resolved.values());
        assertEquals(Set.of(), resolved.languages());
        assertEquals(Set.of(), resolved.buildSystems());
    }
}
