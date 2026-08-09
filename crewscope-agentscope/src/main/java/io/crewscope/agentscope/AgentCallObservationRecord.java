package io.crewscope.agentscope;

import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Content-free model observation; it is telemetry and never an M3 AgentRun business fact. */
public record AgentCallObservationRecord(
        Instant occurredAt,
        AgentCallObservationEvent event,
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        ConversationId conversationId,
        AgentRuntimeSessionId runtimeSessionId,
        RuntimeInvocationId invocationId,
        UUID correlationId,
        Optional<String> traceId,
        Optional<String> spanId,
        String modelName,
        AgentModelRole modelRole,
        int attempt,
        int maxAttempts,
        int retryCount,
        boolean fallbackUsed,
        int inputTokens,
        int outputTokens,
        int cachedTokens,
        int totalTokens,
        long latencyMillis,
        Optional<String> safeErrorCode) {

    public AgentCallObservationRecord {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        event = Objects.requireNonNull(event, "event");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        runtimeSessionId = Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        traceId = Objects.requireNonNull(traceId, "traceId");
        spanId = Objects.requireNonNull(spanId, "spanId");
        modelName = safeModelName(modelName);
        modelRole = Objects.requireNonNull(modelRole, "modelRole");
        safeErrorCode = Objects.requireNonNull(safeErrorCode, "safeErrorCode")
                .map(AgentCallObservationRecord::safeCode);
        if (attempt < 0 || maxAttempts < 1 || retryCount < 0 || inputTokens < 0
                || outputTokens < 0 || cachedTokens < 0 || totalTokens < 0
                || latencyMillis < 0) {
            throw new IllegalArgumentException("observation numeric values must not be negative");
        }
        if (totalTokens != inputTokens + outputTokens || cachedTokens > inputTokens) {
            throw new IllegalArgumentException("token usage must be internally consistent");
        }
    }

    static AgentCallObservationRecord from(
            Instant occurredAt,
            AgentCallObservationEvent event,
            PlatformExecutionContext context,
            AgentCallTraceContext trace,
            String modelName,
            AgentModelRole modelRole,
            int attempt,
            int maxAttempts,
            int retryCount,
            boolean fallbackUsed,
            AgentCallTokenUsage usage,
            long latencyMillis,
            Optional<String> safeErrorCode) {
        return new AgentCallObservationRecord(
                occurredAt,
                event,
                context.scope().organizationId(),
                context.scope().teamId(),
                context.scope().workspaceId(),
                context.conversationId(),
                context.runtimeSessionId(),
                context.invocationId(),
                context.correlationId(),
                trace.traceId(),
                trace.spanId(),
                modelName,
                modelRole,
                attempt,
                maxAttempts,
                retryCount,
                fallbackUsed,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedTokens(),
                usage.totalTokens(),
                latencyMillis,
                safeErrorCode);
    }

    static String safeModelName(String value) {
        String candidate = Objects.requireNonNullElse(value, "unknown").strip();
        if (candidate.isEmpty()) {
            return "unknown";
        }
        StringBuilder safe = new StringBuilder(Math.min(candidate.length(), 200));
        candidate.codePoints().limit(200).forEach(codePoint -> safe.appendCodePoint(
                Character.isISOControl(codePoint) ? '_' : codePoint));
        return safe.toString();
    }

    private static String safeCode(String value) {
        if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("safeErrorCode must be a stable uppercase code");
        }
        return value;
    }
}
