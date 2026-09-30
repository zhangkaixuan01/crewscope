package io.crewscope.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Drops tool-protocol violations from the reasoning message list before it reaches the model.
 *
 * <p>Defect 10 (M9b-Q02): two harness paths can leave the durable memory in a state OpenAI-compatible
 * providers reject with 400 "Messages with role 'tool' must be a response to a preceding message with
 * 'tool_calls'" — an interrupt/resume boundary appends a late tool result whose parent assistant
 * tool_use was rolled back, and the conversation compactor's tail cut removes the parent while keeping
 * the result. Every subsequent model request then carries the orphan until the retry budget exhausts
 * and the execution dies as TASK_EXECUTION_FAILED.
 *
 * <p>This middleware is the send-side repair: one linear scan per reasoning request, no mutation of
 * the durable memory (the harness owns that state machine). It tracks pending tool_use ids and (a)
 * drops tool-result blocks no pending call can claim, dropping the whole TOOL message when nothing
 * remains, and (b) strips still-unanswered tool_use blocks from their assistant message once another
 * turn starts, dropping that message when nothing remains. A list that already satisfies the protocol
 * returns unchanged — the identical reference — so healthy sessions pay only the scan.
 */
public final class ToolMessageProtocolRepairMiddleware implements MiddlewareBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolMessageProtocolRepairMiddleware.class);

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext runtimeContext,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<Msg> repaired = repair(input.messages());
        return repaired == input.messages()
                ? next.apply(input)
                : next.apply(new ReasoningInput(repaired, input.tools(), input.options()));
    }

    /**
     * Returns the original reference when the list already satisfies the tool protocol; otherwise a
     * rebuilt list without orphaned tool results and unanswered tool calls. Counts stay in the log
     * line only — no ids, names or content leave the process through logging.
     */
    static List<Msg> repair(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Msg> repaired = new ArrayList<>(messages.size());
        Set<String> openToolCalls = new LinkedHashSet<>();
        int toolCallSource = -1;
        int droppedResults = 0;
        int removedCalls = 0;
        int droppedMessages = 0;

        for (Msg message : messages) {
            if (message.getRole() == MsgRole.TOOL) {
                List<ContentBlock> content = contentOf(message);
                List<ContentBlock> kept = null;
                for (int i = 0; i < content.size(); i++) {
                    ContentBlock block = content.get(i);
                    if (block instanceof ToolResultBlock result
                            && (result.getId() == null || !openToolCalls.contains(result.getId()))) {
                        // No pending tool_call can claim this result: its parent assistant message
                        // was rolled back at the interrupt boundary or truncated by compaction.
                        droppedResults++;
                        if (kept == null) {
                            kept = new ArrayList<>(content.subList(0, i));
                        }
                        continue;
                    }
                    if (kept != null) {
                        kept.add(block);
                    }
                    if (block instanceof ToolResultBlock result && result.getId() != null) {
                        openToolCalls.remove(result.getId());
                    }
                }
                if (kept == null) {
                    repaired.add(message);
                } else if (kept.isEmpty()) {
                    droppedMessages++;
                } else {
                    repaired.add(rebuild(message, kept));
                }
                if (openToolCalls.isEmpty()) {
                    toolCallSource = -1;
                }
                continue;
            }
            if (!openToolCalls.isEmpty() && toolCallSource >= 0) {
                // A new turn starts while calls stay unanswered; strip them from their assistant
                // message (removing it entirely when no content block remains).
                Msg source = repaired.remove(toolCallSource);
                List<ContentBlock> stripped = stripUnanswered(contentOf(source), openToolCalls);
                removedCalls += contentOf(source).size() - stripped.size();
                if (stripped.isEmpty()) {
                    droppedMessages++;
                } else {
                    repaired.add(toolCallSource, rebuild(source, stripped));
                }
                openToolCalls.clear();
                toolCallSource = -1;
            }
            repaired.add(message);
            List<String> calls = toolCallIds(contentOf(message));
            if (!calls.isEmpty()) {
                openToolCalls.addAll(calls);
                toolCallSource = repaired.size() - 1;
            }
        }
        if (!openToolCalls.isEmpty() && toolCallSource >= 0) {
            // The list ends mid-turn: the trailing unanswered calls get the same strip.
            Msg source = repaired.remove(toolCallSource);
            List<ContentBlock> stripped = stripUnanswered(contentOf(source), openToolCalls);
            removedCalls += contentOf(source).size() - stripped.size();
            if (stripped.isEmpty()) {
                droppedMessages++;
            } else {
                repaired.add(toolCallSource, rebuild(source, stripped));
            }
        }
        if (droppedResults == 0 && removedCalls == 0) {
            return messages;
        }
        LOGGER.warn(
                "Tool protocol repaired before reasoning: dropped {} orphaned tool result(s), "
                        + "removed {} unanswered tool call(s) across {} dropped message(s)",
                droppedResults, removedCalls, droppedMessages);
        return repaired;
    }

    private static List<ContentBlock> contentOf(Msg message) {
        List<ContentBlock> content = message.getContent();
        return content == null ? List.<ContentBlock>of() : content;
    }

    private static List<String> toolCallIds(List<ContentBlock> content) {
        List<String> ids = null;
        for (ContentBlock block : content) {
            if (block instanceof ToolUseBlock use && use.getId() != null) {
                if (ids == null) {
                    ids = new ArrayList<>();
                }
                ids.add(use.getId());
            }
        }
        return ids == null ? List.<String>of() : ids;
    }

    private static List<ContentBlock> stripUnanswered(List<ContentBlock> content, Set<String> ids) {
        List<ContentBlock> kept = new ArrayList<>(content.size());
        for (ContentBlock block : content) {
            if (block instanceof ToolUseBlock use && use.getId() != null && ids.contains(use.getId())) {
                continue;
            }
            kept.add(block);
        }
        return kept;
    }

    private static Msg rebuild(Msg message, List<ContentBlock> content) {
        return Msg.builder()
                .id(message.getId())
                .name(message.getName())
                .role(message.getRole())
                .content(content)
                .metadata(message.getMetadata())
                .timestamp(message.getTimestamp())
                .usage(message.getUsage())
                .generateReason(message.getGenerateReason())
                .build();
    }
}
