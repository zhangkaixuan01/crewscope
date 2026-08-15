package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;
import java.util.concurrent.Flow;

/** Single-subscriber finite stream bound to one server-validated durable AgentRun Segment. */
public final class TaskExecutionHandle {

    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final AgentRunId agentRunId;
    private final long segmentSequence;
    private final AgentRunSegmentKind segmentKind;
    private final Flow.Publisher<TaskExecutionEvent> events;

    public TaskExecutionHandle(
            TaskExecutionRequest request,
            Flow.Publisher<TaskExecutionEvent> events) {
        TaskExecutionRequest required = Objects.requireNonNull(request, "request");
        TaskExecutionRuntimeFacts facts = required.facts();
        this.taskExecutionId = facts.execution().id();
        this.attempt = facts.execution().attempt();
        this.agentRunId = facts.agentRun().id();
        this.segmentSequence = facts.agentRun().currentSegment().sequence();
        this.segmentKind = facts.agentRun().currentSegment().kind();
        this.events = new TaskExecutionEventPublisher(
                taskExecutionId,
                attempt,
                agentRunId,
                segmentSequence,
                segmentKind,
                Objects.requireNonNull(events, "events"));
    }

    public TaskExecutionId taskExecutionId() {
        return taskExecutionId;
    }

    public int attempt() {
        return attempt;
    }

    public AgentRunId agentRunId() {
        return agentRunId;
    }

    public long segmentSequence() {
        return segmentSequence;
    }

    public AgentRunSegmentKind segmentKind() {
        return segmentKind;
    }

    public Flow.Publisher<TaskExecutionEvent> events() {
        return events;
    }
}
