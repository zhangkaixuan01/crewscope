package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.crewscope.domain.runtime.RuntimeCapability;
import io.crewscope.application.execution.RuntimeDescriptor;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentScopeRuntimeProfileTest {

    @Test
    void pinsAgentScopeJavaTwoDescriptorAndOnlyWiredM2Capabilities() {
        assertEquals(
                new RuntimeDescriptor("agentscope-java-native", "AgentScope Java", "2.0.0"),
                AgentScopeRuntimeProfile.descriptor());
        assertEquals(
                Set.of(
                        RuntimeCapability.CONVERSATION,
                        RuntimeCapability.STREAMING,
                        RuntimeCapability.STRUCTURED_OUTPUT,
                        RuntimeCapability.INTERRUPT_RESUME,
                        RuntimeCapability.CANCEL,
                        RuntimeCapability.SESSION_STATE),
                AgentScopeRuntimeProfile.capabilities().values());
        assertFalse(AgentScopeRuntimeProfile.capabilities().supports(RuntimeCapability.PLAN));
        assertFalse(AgentScopeRuntimeProfile.capabilities().supports(RuntimeCapability.SANDBOX));
        assertFalse(AgentScopeRuntimeProfile.capabilities().supports(RuntimeCapability.SUBAGENT));
    }
}
