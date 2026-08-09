package io.crewscope.agentscope.agui;

import io.agentscope.core.agui.event.AguiEvent;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Applies a strict outbound allowlist to events produced by the generic AgentScope adapter. */
final class AguiEventSanitizer {

    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");

    Optional<AguiEvent> sanitize(AguiEvent event) {
        AguiEvent required = Objects.requireNonNull(event, "event");
        return switch (required.getType()) {
            case RUN_STARTED -> Optional.of(required);
            case RUN_FINISHED -> Optional.of(
                    new AguiEvent.RunFinished(required.getThreadId(), required.getRunId()));
            case TEXT_MESSAGE_START -> Optional.of(sanitizeTextStart(
                    (AguiEvent.TextMessageStart) required));
            case TEXT_MESSAGE_CONTENT -> Optional.of(sanitizeTextContent(
                    (AguiEvent.TextMessageContent) required));
            case TEXT_MESSAGE_END -> Optional.of(sanitizeTextEnd(
                    (AguiEvent.TextMessageEnd) required));
            case TOOL_CALL_START -> Optional.of(sanitizeToolStart(
                    (AguiEvent.ToolCallStart) required));
            case TOOL_CALL_END -> Optional.of(sanitizeToolEnd(
                    (AguiEvent.ToolCallEnd) required));
            case TOOL_CALL_RESULT -> Optional.of(sanitizeToolResult(
                    (AguiEvent.ToolCallResult) required));
            case RUN_ERROR -> Optional.of(new AguiEvent.RunError(
                    required.getThreadId(),
                    required.getRunId(),
                    "Agent execution failed",
                    "AGENT_EXECUTION_FAILED"));
            // Args, raw Tool results, State, Custom, snapshots, chunks and all Reasoning variants
            // can carry unbounded model/provider data and are never forwarded by the M2 bridge.
            default -> Optional.empty();
        };
    }

    private AguiEvent sanitizeTextStart(AguiEvent.TextMessageStart event) {
        return new AguiEvent.TextMessageStart(
                event.threadId(),
                event.runId(),
                opaqueId("message", event.runId(), event.messageId()),
                "assistant");
    }

    private AguiEvent sanitizeTextContent(AguiEvent.TextMessageContent event) {
        return new AguiEvent.TextMessageContent(
                event.threadId(),
                event.runId(),
                opaqueId("message", event.runId(), event.messageId()),
                event.delta());
    }

    private AguiEvent sanitizeTextEnd(AguiEvent.TextMessageEnd event) {
        return new AguiEvent.TextMessageEnd(
                event.threadId(),
                event.runId(),
                opaqueId("message", event.runId(), event.messageId()));
    }

    private AguiEvent sanitizeToolStart(AguiEvent.ToolCallStart event) {
        String safeName = TOOL_NAME.matcher(event.toolCallName()).matches()
                ? event.toolCallName()
                : "unknown_tool";
        return new AguiEvent.ToolCallStart(
                event.threadId(),
                event.runId(),
                opaqueId("tool", event.runId(), event.toolCallId()),
                safeName);
    }

    private AguiEvent sanitizeToolEnd(AguiEvent.ToolCallEnd event) {
        return new AguiEvent.ToolCallEnd(
                event.threadId(),
                event.runId(),
                opaqueId("tool", event.runId(), event.toolCallId()));
    }

    private AguiEvent sanitizeToolResult(AguiEvent.ToolCallResult event) {
        return new AguiEvent.ToolCallResult(
                event.threadId(),
                event.runId(),
                opaqueId("tool", event.runId(), event.toolCallId()),
                null,
                "tool",
                null);
    }

    private String opaqueId(String kind, String runId, String sourceId) {
        String seed = "crewscope:agui:v1:" + kind + ':' + runId + ':' + sourceId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
