package io.crewscope.server.config.application;

import io.crewscope.agentscope.AgentExecutionAuditRecord;
import io.crewscope.agentscope.AgentExecutionAuditSink;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits only the content-free fields defined by AgentExecutionAuditRecord. */
final class StructuredLoggingAgentExecutionAuditSink implements AgentExecutionAuditSink {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(StructuredLoggingAgentExecutionAuditSink.class);

  @Override
  public void record(AgentExecutionAuditRecord record) {
    AgentExecutionAuditRecord required = Objects.requireNonNull(record, "record");
    LOGGER.info(
        "agent_execution_audit phase={} outcome={} organizationId={} teamId={} workspaceId={} "
            + "conversationId={} runtimeSessionId={} invocationId={} requestPrincipalId={} "
            + "correlationId={} toolNames={} itemCount={} failureType={}",
        required.phase(),
        required.outcome(),
        required.organizationId(),
        required.teamId(),
        required.workspaceId(),
        required.conversationId(),
        required.runtimeSessionId(),
        required.invocationId(),
        required.requestPrincipalId(),
        required.correlationId(),
        required.toolNames(),
        required.itemCount(),
        required.safeFailureType().orElse("none"));
  }
}
