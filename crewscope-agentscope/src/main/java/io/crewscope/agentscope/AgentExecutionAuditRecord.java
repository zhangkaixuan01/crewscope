package io.crewscope.agentscope;

import io.crewscope.application.execution.PlatformExecutionContext;
import io.crewscope.application.execution.RuntimeInvocationId;
import io.crewscope.domain.conversation.AgentRuntimeSessionId;
import io.crewscope.domain.conversation.ConversationId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Content-free audit record containing only stable identifiers and low-risk execution metadata. */
public record AgentExecutionAuditRecord(
        Instant occurredAt,
        AgentExecutionAuditPhase phase,
        AgentExecutionAuditOutcome outcome,
        OrganizationId organizationId,
        TeamId teamId,
        WorkspaceId workspaceId,
        ConversationId conversationId,
        AgentRuntimeSessionId runtimeSessionId,
        RuntimeInvocationId invocationId,
        PrincipalId requestPrincipalId,
        UUID correlationId,
        Set<String> toolNames,
        int itemCount,
        Optional<String> safeFailureType) {

    private static final int MAX_RECORDED_TOOL_NAMES = 100;
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final Pattern SAFE_FAILURE_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public AgentExecutionAuditRecord {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        phase = Objects.requireNonNull(phase, "phase");
        outcome = Objects.requireNonNull(outcome, "outcome");
        organizationId = Objects.requireNonNull(organizationId, "organizationId");
        teamId = Objects.requireNonNull(teamId, "teamId");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        runtimeSessionId = Objects.requireNonNull(runtimeSessionId, "runtimeSessionId");
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        requestPrincipalId = Objects.requireNonNull(
                requestPrincipalId, "requestPrincipalId");
        correlationId = Objects.requireNonNull(correlationId, "correlationId");
        toolNames = safeToolNames(toolNames);
        safeFailureType = Objects.requireNonNull(safeFailureType, "safeFailureType")
                .map(AgentExecutionAuditRecord::requireSafeFailureType);
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must not be negative");
        }
    }

    private static Set<String> safeToolNames(Set<String> values) {
        Set<String> required = Objects.requireNonNull(values, "toolNames");
        LinkedHashSet<String> safe = new LinkedHashSet<>();
        for (String value : required) {
            if (safe.size() >= MAX_RECORDED_TOOL_NAMES) {
                break;
            }
            String candidate = Objects.requireNonNullElse(value, "");
            safe.add(TOOL_NAME.matcher(candidate).matches() ? candidate : "unknown_tool");
        }
        return Collections.unmodifiableSet(safe);
    }

    private static String requireSafeFailureType(String value) {
        String required = Objects.requireNonNull(value, "safeFailureType");
        if (!SAFE_FAILURE_TYPE.matcher(required).matches()) {
            throw new IllegalArgumentException(
                    "safeFailureType must be a stable uppercase code");
        }
        return required;
    }

    static AgentExecutionAuditRecord from(
            Instant occurredAt,
            PlatformExecutionContext context,
            AgentExecutionAuditPhase phase,
            AgentExecutionAuditOutcome outcome,
            Set<String> toolNames,
            int itemCount,
            Optional<String> safeFailureType) {
        return new AgentExecutionAuditRecord(
                occurredAt,
                phase,
                outcome,
                context.scope().organizationId(),
                context.scope().teamId(),
                context.scope().workspaceId(),
                context.conversationId(),
                context.runtimeSessionId(),
                context.invocationId(),
                context.requestPrincipalId(),
                context.correlationId(),
                toolNames,
                itemCount,
                safeFailureType);
    }
}
