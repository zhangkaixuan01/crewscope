package io.crewscope.domain.agent;

import java.util.Objects;

/** Resolves execution scope from durable server facts; the initiating client cannot override it. */
public final class AgentExecutionScopePolicy {

    private AgentExecutionScopePolicy() {}

    public static AgentExecutionScope resolve(AgentExecutionScopeFacts facts) {
        AgentExecutionScopeFacts required = Objects.requireNonNull(facts, "facts");
        return required.sharedTeamWorkItem()
                        || required.teamResponsibilityChain()
                        || required.teamBudget()
                        || required.teamServiceLevel()
                ? AgentExecutionScope.TEAM
                : AgentExecutionScope.PERSONAL;
    }
}
