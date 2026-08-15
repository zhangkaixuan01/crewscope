package io.crewscope.agentscope.task;

import io.crewscope.application.execution.RuntimeDescriptor;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;

/** Capabilities implemented by the restricted AgentScope Java M3 Task runtime. */
public final class AgentScopeTaskRuntimeProfile {

    private static final RuntimeDescriptor DESCRIPTOR =
            new RuntimeDescriptor("agentscope-java-task", "AgentScope Java Task", "2.0.0");
    private static final RuntimeCapabilities CAPABILITIES = RuntimeCapabilities.of(
            RuntimeCapability.TASK_EXECUTION,
            RuntimeCapability.STREAMING,
            RuntimeCapability.DURABLE_EVENT_STREAM,
            RuntimeCapability.PAUSE_RESUME,
            RuntimeCapability.CANCEL,
            RuntimeCapability.SESSION_STATE,
            RuntimeCapability.PLAN);

    private AgentScopeTaskRuntimeProfile() {}

    public static RuntimeDescriptor descriptor() {
        return DESCRIPTOR;
    }

    public static RuntimeCapabilities capabilities() {
        return CAPABILITIES;
    }
}
