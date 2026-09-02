package io.crewscope.agentscope.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Contract tests for the closed Task tool allowlist boundary. */
class AgentScopeTaskToolPolicyTest {

    @Test
    void acceptsAllowedToolAndRejectsUnknownOrMalformedNames() {
        Set<String> allowed = Set.of("fixture_execute", "plan_exit");

        assertEquals("fixture_execute", AgentScopeTaskToolPolicy.requireAllowed(
                allowed, " fixture_execute "));
        assertThrows(IllegalArgumentException.class,
                () -> AgentScopeTaskToolPolicy.requireAllowed(allowed, "shell_exec"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentScopeTaskToolPolicy.requireAllowed(allowed, "fixture_\u0001execute"));
    }
}
