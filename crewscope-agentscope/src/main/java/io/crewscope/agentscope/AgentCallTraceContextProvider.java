package io.crewscope.agentscope;

/** Obtains the current tracing identifiers without coupling the adapter to a tracing SDK. */
@FunctionalInterface
public interface AgentCallTraceContextProvider {

    AgentCallTraceContext current();

    static AgentCallTraceContextProvider none() {
        return AgentCallTraceContext::empty;
    }
}
