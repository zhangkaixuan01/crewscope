package io.crewscope.domain.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentExecutionScopePolicyTest {

    @Test
    void usesOnlyServerTeamFactsToResolveExecutionScope() {
        assertEquals(
                AgentExecutionScope.PERSONAL,
                AgentExecutionScopePolicy.resolve(
                        new AgentExecutionScopeFacts(false, false, false, false)));
        assertEquals(
                AgentExecutionScope.TEAM,
                AgentExecutionScopePolicy.resolve(
                        new AgentExecutionScopeFacts(true, false, false, false)));
        assertEquals(
                AgentExecutionScope.TEAM,
                AgentExecutionScopePolicy.resolve(
                        new AgentExecutionScopeFacts(false, true, false, false)));
        assertEquals(
                AgentExecutionScope.TEAM,
                AgentExecutionScopePolicy.resolve(
                        new AgentExecutionScopeFacts(false, false, true, false)));
        assertEquals(
                AgentExecutionScope.TEAM,
                AgentExecutionScopePolicy.resolve(
                        new AgentExecutionScopeFacts(false, false, false, true)));
    }
}
