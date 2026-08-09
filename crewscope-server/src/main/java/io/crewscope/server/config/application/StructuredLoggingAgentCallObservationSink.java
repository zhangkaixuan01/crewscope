package io.crewscope.server.config.application;

import io.crewscope.agentscope.AgentCallObservationRecord;
import io.crewscope.agentscope.AgentCallObservationSink;
import io.crewscope.server.observability.AgentCallObservabilityMetrics;
import io.crewscope.server.observability.StructuredLogSanitizer;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/** Emits content-free Agent model observations and their low-cardinality metric projection. */
final class StructuredLoggingAgentCallObservationSink implements AgentCallObservationSink {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(StructuredLoggingAgentCallObservationSink.class);

  private final AgentCallObservabilityMetrics metrics;

  StructuredLoggingAgentCallObservationSink(AgentCallObservabilityMetrics metrics) {
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  @Override
  public void record(AgentCallObservationRecord record) {
    AgentCallObservationRecord required = Objects.requireNonNull(record, "record");
    try {
      metrics.record(required);
    } catch (RuntimeException ignored) {
      // A registry outage must not suppress the correlation log for the same call.
    }
    LoggingEventBuilder event = LOGGER.atInfo()
        .addKeyValue("event", "agent_model_call")
        .addKeyValue("callEvent", required.event())
        .addKeyValue("organizationId", required.organizationId())
        .addKeyValue("teamId", required.teamId())
        .addKeyValue("workspaceId", required.workspaceId())
        .addKeyValue("conversationId", required.conversationId())
        .addKeyValue("runtimeSessionId", required.runtimeSessionId())
        .addKeyValue("invocationId", required.invocationId())
        .addKeyValue("correlationId", required.correlationId())
        .addKeyValue("model", StructuredLogSanitizer.sanitize("model", required.modelName()))
        .addKeyValue("modelRole", required.modelRole())
        .addKeyValue("attempt", required.attempt())
        .addKeyValue("maxAttempts", required.maxAttempts())
        .addKeyValue("retryCount", required.retryCount())
        .addKeyValue("fallbackUsed", required.fallbackUsed())
        .addKeyValue("inputTokens", required.inputTokens())
        .addKeyValue("outputTokens", required.outputTokens())
        .addKeyValue("cachedTokens", required.cachedTokens())
        .addKeyValue("totalTokens", required.totalTokens())
        .addKeyValue("latencyMs", required.latencyMillis());
    if (required.traceId().isPresent()) {
      event = event.addKeyValue("traceId", required.traceId().orElseThrow())
          .addKeyValue("spanId", required.spanId().orElseThrow());
    }
    if (required.safeErrorCode().isPresent()) {
      event = event.addKeyValue("errorCode", required.safeErrorCode().orElseThrow());
    }
    event.log("Agent model call observed");
  }
}
