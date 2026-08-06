package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M0-S01 integration evidence for the AgentScope 2.0.0 HarnessAgent baseline. */
@Tag("integration")
class HarnessAgentM0S01IntegrationTest {

    private static final String USER_ID = "member-zhang";
    private static final String SESSION_ID = "conversation-crw-1024";
    private static final String STATE_KEY = "agent_state";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path workspace;

    @Test
    void singleTurnRecordsFineGrainedEventsSessionKeyAndAgentState() {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel("single-turn-complete");
        RuntimeContext context = sessionContext();

        List<AgentEvent> events;
        try (HarnessAgent agent = newAgent(model, stateStore)) {
            events =
                    agent.streamEvents("Initialize the CrewScope runtime baseline", context)
                            .collectList()
                            .block(TIMEOUT);
        }

        List<AgentEventType> eventSequence = eventTypes(events);
        AgentStartEvent start = (AgentStartEvent) events.get(0);
        AgentResultEvent result = findResult(events);
        AgentState state = loadState(stateStore);

        assertEquals(
                List.of(
                        AgentEventType.AGENT_START,
                        AgentEventType.MODEL_CALL_START,
                        AgentEventType.TEXT_BLOCK_START,
                        AgentEventType.TEXT_BLOCK_DELTA,
                        AgentEventType.TEXT_BLOCK_END,
                        AgentEventType.MODEL_CALL_END,
                        AgentEventType.AGENT_RESULT,
                        AgentEventType.AGENT_END),
                eventSequence);
        // AgentScope 2.0.0 does not populate sessionId on a top-level AGENT_START event. CrewScope
        // must enrich outward events from the trusted RuntimeContext in M0-S02.
        assertNull(start.getSessionId());
        assertFalse(start.getReplyId().isBlank());
        assertEquals("single-turn-complete", result.getResult().getTextContent());
        assertEquals(USER_ID, state.getUserId());
        assertEquals(SESSION_ID, state.getSessionId());
        assertEquals(2, state.getContext().size());
        assertTrue(stateStore.exists(USER_ID, SESSION_ID));
        assertTrue(stateStore.listSessionIds(USER_ID).contains(SESSION_ID));

        System.out.printf(
                "M0-S01 single-turn evidence: slot=(%s,%s), events=%s, contextSize=%d%n",
                USER_ID, SESSION_ID, eventSequence, state.getContext().size());
    }

    @Test
    void multiTurnRestoresAgentStateAcrossHarnessInstances() {
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        ScriptedModel model = new ScriptedModel("baseline-noted", "history-restored");
        RuntimeContext context = sessionContext();

        try (HarnessAgent firstAgent = newAgent(model, stateStore)) {
            Msg firstReply =
                    firstAgent
                            .call("Remember delivery code CRW-1024", context)
                            .block(TIMEOUT);
            assertEquals("baseline-noted", firstReply.getTextContent());
        }

        AgentState firstTurnState = loadState(stateStore);
        assertEquals(2, firstTurnState.getContext().size());

        // A new HarnessAgent instance must reload the same (userId, sessionId) state slot.
        try (HarnessAgent secondAgent = newAgent(model, stateStore)) {
            Msg secondReply =
                    secondAgent.call("Which delivery code did I give you?", context).block(TIMEOUT);
            assertEquals("history-restored", secondReply.getTextContent());
        }

        AgentState secondTurnState = loadState(stateStore);
        String secondModelInput = allText(model.request(1));

        assertEquals(2, model.callCount());
        assertTrue(secondModelInput.contains("Remember delivery code CRW-1024"));
        assertTrue(secondModelInput.contains("baseline-noted"));
        assertTrue(secondModelInput.contains("Which delivery code did I give you?"));
        assertEquals(4, secondTurnState.getContext().size());
        assertFalse(secondTurnState.getReplyId().isBlank());

        System.out.printf(
                "M0-S01 multi-turn evidence: slot=(%s,%s), modelCalls=%d, contextSize=%d%n",
                USER_ID, SESSION_ID, model.callCount(), secondTurnState.getContext().size());
    }

    private HarnessAgent newAgent(
            ScriptedModel model, InMemoryAgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("crewscope-m0-s01-agent")
                .sysPrompt("You are the deterministic CrewScope M0 runtime probe.")
                .model(model)
                .workspace(workspace)
                .stateStore(stateStore)
                .disableFilesystemTools()
                .disableShellTool()
                .disableSubagents()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .enableAgentTracingLog(false)
                .build();
    }

    private static RuntimeContext sessionContext() {
        return RuntimeContext.builder().userId(USER_ID).sessionId(SESSION_ID).build();
    }

    private static AgentState loadState(InMemoryAgentStateStore stateStore) {
        return stateStore
                .get(USER_ID, SESSION_ID, STATE_KEY, AgentState.class)
                .orElseThrow(() -> new AssertionError("AgentState was not persisted"));
    }

    private static List<AgentEventType> eventTypes(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::getType).toList();
    }

    private static AgentResultEvent findResult(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("AGENT_RESULT event was not emitted"));
    }

    private static String allText(List<Msg> messages) {
        return messages.stream().map(Msg::getTextContent).reduce("", (left, right) -> left + right);
    }
}
