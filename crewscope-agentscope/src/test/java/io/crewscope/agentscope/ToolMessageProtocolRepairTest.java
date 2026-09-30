package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-function coverage for the send-side tool-protocol repair (M9b-Q02 defect 10): the
 * interrupt/compaction orphans that OpenAI-compatible providers reject with 400 must disappear,
 * and a healthy list must return as the identical reference.
 */
class ToolMessageProtocolRepairTest {

    @Test
    void healthyConversationReturnsTheIdenticalReference() {
        Msg user = user("plan the change");
        Msg assistant = assistant("thinking", toolUse("call-1", "todo_write"));
        Msg tool = tool(toolResult("call-1", "todo_write"));
        Msg reply = assistant("done", null);

        List<Msg> messages = List.of(user, assistant, tool, reply);

        assertSame(messages, ToolMessageProtocolRepairMiddleware.repair(messages));
    }

    @Test
    void orphanedToolResultFromARolledBackParentIsDroppedEntirely() {
        // Defect 10 shape: segment 1's assistant tool_use was rolled back at the interrupt
        // boundary, but the executed tool's late result was appended after resume.
        Msg user = user("plan the change");
        Msg assistant = assistant("status first", toolUse("call-live", "repository_git_status"));
        Msg answered = tool(toolResult("call-live", "repository_git_status"));
        Msg orphan = tool(toolResult("call-orphan", "todo_write"));
        Msg reply = assistant("structured output", null);

        List<Msg> repaired = ToolMessageProtocolRepairMiddleware.repair(
                List.of(user, assistant, answered, orphan, reply));

        assertEquals(4, repaired.size());
        assertSame(user, repaired.get(0));
        assertSame(assistant, repaired.get(1));
        assertSame(answered, repaired.get(2));
        assertSame(reply, repaired.get(3));
    }

    @Test
    void partiallyOrphanedToolMessageKeepsItsAnsweredBlocks() {
        Msg assistant = assistant("two calls", toolUse("call-1", "todo_write"), toolUse("call-2", "fs_read"));
        Msg tool = tool(toolResult("call-1", "todo_write"), toolResult("call-ghost", "todo_write"));
        Msg reply = assistant("after", null);

        List<Msg> repaired = ToolMessageProtocolRepairMiddleware.repair(List.of(assistant, tool, reply));

        assertEquals(3, repaired.size());
        List<ContentBlock> kept = repaired.get(1).getContent();
        assertEquals(1, kept.size());
        assertEquals("call-1", ((ToolResultBlock) kept.get(0)).getId());
        // The ghost block was dropped, but call-2 was never answered before the reply started,
        // so the protocol requires stripping its tool_use from the assistant message too.
        List<ContentBlock> source = repaired.get(0).getContent();
        assertEquals(2, source.size());
        assertEquals("two calls", ((TextBlock) source.get(0)).getText());
        assertEquals("call-1", ((ToolUseBlock) source.get(1)).getId());
    }

    @Test
    void unansweredToolUseIsStrippedWhenTheNextTurnStarts() {
        Msg assistant = assistant("calls a tool", toolUse("call-unanswered", "todo_write"));
        Msg user = user("resume with confirmation");

        List<Msg> repaired = ToolMessageProtocolRepairMiddleware.repair(List.of(assistant, user));

        assertEquals(2, repaired.size());
        List<ContentBlock> content = repaired.get(0).getContent();
        assertEquals(1, content.size());
        assertEquals("calls a tool", ((TextBlock) content.get(0)).getText());
        assertSame(user, repaired.get(1));
    }

    @Test
    void assistantMessageWithOnlyUnansweredCallsIsRemovedEntirely() {
        Msg assistant = assistant(null, toolUse("call-only", "todo_write"));
        Msg user = user("next turn");

        List<Msg> repaired = ToolMessageProtocolRepairMiddleware.repair(List.of(assistant, user));

        assertEquals(1, repaired.size());
        assertSame(user, repaired.get(0));
    }

    @Test
    void trailingUnansweredToolUseIsStrippedAtTheEndOfTheList() {
        // Compaction's tail cut can leave the list ending on an unanswered tool_use.
        Msg assistant = assistant("partial text", toolUse("call-tail-1", "fs_write"), toolUse("call-tail-2", "fs_read"));

        List<Msg> repaired = ToolMessageProtocolRepairMiddleware.repair(List.of(assistant));

        assertEquals(1, repaired.size());
        List<ContentBlock> content = repaired.get(0).getContent();
        assertEquals(1, content.size());
        assertEquals("partial text", ((TextBlock) content.get(0)).getText());
    }

    @Test
    void duplicateAnswerIsOrphanedWhileTheStillPendingCallIsStrippedAtTheNextTurn() {
        Msg assistant = assistant("two calls", toolUse("call-a", "todo_write"), toolUse("call-b", "fs_read"));
        Msg answerA = tool(toolResult("call-a", "todo_write"));
        // call-a is no longer pending, so a second result for it is an orphan again.
        Msg duplicateA = tool(toolResult("call-a", "todo_write"));
        Msg reply = assistant("after", null);

        List<Msg> repaired = ToolMessageProtocolRepairMiddleware.repair(
                List.of(assistant, answerA, duplicateA, reply));

        // duplicateA dropped; call-b stays unanswered when the reply starts, so its tool_use is
        // stripped — but the answered call-a keeps its tool_use so answerA still has its parent.
        assertEquals(3, repaired.size());
        assertSame(answerA, repaired.get(1));
        assertSame(reply, repaired.get(2));
        List<ContentBlock> content = repaired.get(0).getContent();
        assertEquals(2, content.size());
        assertEquals("two calls", ((TextBlock) content.get(0)).getText());
        assertEquals("call-a", ((ToolUseBlock) content.get(1)).getId());
    }

    @Test
    void repairedListSatisfiesTheProtocolItself() {
        // Idempotence through the library's own message validation: rebuilding keeps role/content
        // shapes the serializer accepts, and a second pass finds nothing left to repair.
        Msg assistant = assistant("mixed", toolUse("call-x", "todo_write"));
        Msg orphan = tool(toolResult("call-ghost", "todo_write"));
        Msg next = assistant("next", null);

        List<Msg> repaired = ToolMessageProtocolRepairMiddleware.repair(List.of(assistant, orphan, next));

        assertSame(repaired, ToolMessageProtocolRepairMiddleware.repair(repaired));
    }

    private static Msg user(String text) {
        return msg(MsgRole.USER, text, null);
    }

    private static Msg assistant(String text, ToolUseBlock... calls) {
        return msg(MsgRole.ASSISTANT, text, calls);
    }

    private static Msg msg(MsgRole role, String text, ToolUseBlock[] calls) {
        List<ContentBlock> blocks = new java.util.ArrayList<>();
        if (text != null) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        if (calls != null) {
            blocks.addAll(List.of(calls));
        }
        return Msg.builder().id("msg-" + COUNTER++).role(role).content(blocks).build();
    }

    private static Msg tool(ToolResultBlock... results) {
        return Msg.builder().id("msg-" + COUNTER++).role(MsgRole.TOOL)
                .content(List.of(results)).build();
    }

    private static ToolUseBlock toolUse(String id, String name) {
        return new ToolUseBlock(id, name, Map.of("input", "value"));
    }

    private static ToolResultBlock toolResult(String id, String name) {
        return new ToolResultBlock(id, name, List.of(TextBlock.builder().text("ok").build()));
    }

    private static int COUNTER = 0;
}
