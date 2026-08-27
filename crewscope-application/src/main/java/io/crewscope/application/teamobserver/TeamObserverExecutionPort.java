package io.crewscope.application.teamobserver;

/** Adapter boundary that resolves the fixed TEAM graph and runs its read-only AgentScope loop. */
@FunctionalInterface
public interface TeamObserverExecutionPort {

    TeamObserverExecution execute(TeamObserverExecutionRequest request);
}
