package io.crewscope.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AgentScope 2.0.0 fixture evidence for the raw-event visibility allowlist. */
class AgentScopeEventMapperM2I06Test {

    @Test
    void exposesOnlyTopLevelPublicTextAndAbsorbsDuplicateIds() {
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();
        TextBlockDeltaEvent text = new TextBlockDeltaEvent("reply", "block", "public text");

        AgentScopeEventMapper.PublicText mapped = assertInstanceOf(
                AgentScopeEventMapper.PublicText.class, mapper.map(text));

        assertEquals("public text", mapped.delta());
        assertEquals(AgentScopeEventMapper.Ignored.INSTANCE, mapper.map(text));

        AgentEvent child = new TextBlockDeltaEvent("reply", "child", "child secret")
                .withSource("main/researcher");
        assertEquals(AgentScopeEventMapper.Ignored.INSTANCE, mapper.map(child));
    }

    @Test
    void dropsReasoningToolArgumentsResultsAndUnknownCustomEvents() {
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();
        String secret = "api_key=top-secret";

        assertEquals(
                AgentScopeEventMapper.Ignored.INSTANCE,
                mapper.map(new ThinkingBlockDeltaEvent("reply", "thinking", secret)));
        assertEquals(
                AgentScopeEventMapper.Ignored.INSTANCE,
                mapper.map(new ToolCallDeltaEvent(
                        "reply", "tool-call", "github_api", secret)));
        assertEquals(
                AgentScopeEventMapper.Ignored.INSTANCE,
                mapper.map(new ToolResultTextDeltaEvent(
                        "reply", "tool-call", "github_api", secret)));
        assertEquals(
                AgentScopeEventMapper.Ignored.INSTANCE,
                mapper.map(new CustomEvent("future-event", Map.of("secret", secret))));
    }
}
