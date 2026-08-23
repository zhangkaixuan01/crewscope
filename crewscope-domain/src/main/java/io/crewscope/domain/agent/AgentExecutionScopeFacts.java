package io.crewscope.domain.agent;

/** Server-owned task facts used to classify one execution as PERSONAL or TEAM. */
public record AgentExecutionScopeFacts(
        boolean sharedTeamWorkItem,
        boolean teamResponsibilityChain,
        boolean teamBudget,
        boolean teamServiceLevel) {}
