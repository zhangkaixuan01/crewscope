package io.crewscope.agentscope;

import io.crewscope.application.agent.AgentRuntime;
import io.crewscope.application.agent.AgentRuntimeDescriptor;
import java.util.Set;

public final class AgentScopeRuntimeAdapter implements AgentRuntime {

    @Override
    public AgentRuntimeDescriptor descriptor() {
        return new AgentRuntimeDescriptor(
                "AgentScope Java",
                "2.0.0",
                Set.of(
                        "harness",
                        "ag-ui",
                        "structured-output",
                        "plan-mode",
                        "external-tool",
                        "memory",
                        "subagent",
                        "sandbox",
                        "distributed-state"));
    }
}
