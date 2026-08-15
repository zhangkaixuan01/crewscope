package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentInterrupt;
import io.crewscope.domain.task.AgentRun;
import java.util.Objects;

/** Durable Resume result containing no raw interruption token or answer. */
public record AgentRunResumeResult(
        AgentRunResumeStatus status,
        AgentRun agentRun,
        AgentInterrupt interrupt) {

    public AgentRunResumeResult {
        status = Objects.requireNonNull(status, "status");
        agentRun = Objects.requireNonNull(agentRun, "agentRun");
        interrupt = Objects.requireNonNull(interrupt, "interrupt");
    }
}
