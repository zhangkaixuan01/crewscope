package io.crewscope.agentscope;

/** Synchronous durable-or-fail audit boundary; failures stop the intercepted execution. */
@FunctionalInterface
public interface AgentExecutionAuditSink {

    void record(AgentExecutionAuditRecord record);
}
