package io.crewscope.agentscope;

import io.crewscope.application.execution.RuntimeDescriptor;
import io.crewscope.domain.runtime.RuntimeCapabilities;
import io.crewscope.domain.runtime.RuntimeCapability;

/** Pinned M2 capability profile shared by the later AgentScopeNativeRuntime implementation. */
public final class AgentScopeRuntimeProfile {

    private static final RuntimeDescriptor DESCRIPTOR =
            new RuntimeDescriptor("agentscope-java-native", "AgentScope Java", "2.0.0");
    private static final RuntimeCapabilities CAPABILITIES = RuntimeCapabilities.of(
            RuntimeCapability.CONVERSATION,
            RuntimeCapability.STREAMING,
            RuntimeCapability.STRUCTURED_OUTPUT,
            RuntimeCapability.INTERRUPT_RESUME,
            RuntimeCapability.CANCEL,
            RuntimeCapability.SESSION_STATE);

    private AgentScopeRuntimeProfile() {}

    public static RuntimeDescriptor descriptor() {
        return DESCRIPTOR;
    }

    public static RuntimeCapabilities capabilities() {
        return CAPABILITIES;
    }
}
