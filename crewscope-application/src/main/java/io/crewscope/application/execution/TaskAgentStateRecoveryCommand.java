package io.crewscope.application.execution;

import java.util.Objects;

/** Trusted Worker request for rebuilding one Task AgentScope hot-state slot. */
public record TaskAgentStateRecoveryCommand(
        TaskExecutionRuntimeFacts facts,
        TaskAgentStateIdentity identity,
        int candidateLimit) {

    public TaskAgentStateRecoveryCommand {
        facts = Objects.requireNonNull(facts, "facts");
        identity = Objects.requireNonNull(identity, "identity");
        if (candidateLimit < 1 || candidateLimit > 100) {
            throw new IllegalArgumentException("candidateLimit must be between 1 and 100");
        }
        boolean current = identity.taskExecutionId().equals(facts.execution().id().value())
                && identity.agentRunId().equals(facts.agentRun().id().value())
                && identity.userId().equals(facts.runtimeSession().agentScopeKey().userId())
                && identity.sessionId().equals(facts.runtimeSession().agentScopeKey().sessionId())
                && identity.agentVersion().equals(
                        Long.toString(facts.runtimeSession().agentProfileVersion()));
        if (!current) {
            throw new IllegalArgumentException(
                    "Agent state recovery crossed the Task, Run, Agent or Session boundary");
        }
    }
}
