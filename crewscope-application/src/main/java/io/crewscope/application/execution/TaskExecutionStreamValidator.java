package io.crewscope.application.execution;

import io.crewscope.domain.task.AgentRunId;
import io.crewscope.domain.task.AgentRunSegmentKind;
import io.crewscope.domain.task.TaskExecutionId;
import java.util.Objects;

/** Stateful validation of one finite durable AgentRun Segment event stream. */
public final class TaskExecutionStreamValidator {

    private final TaskExecutionId taskExecutionId;
    private final int attempt;
    private final AgentRunId agentRunId;
    private final long segmentSequence;
    private final AgentRunSegmentKind segmentKind;
    private long nextSequence = 1;
    private boolean started;
    private TaskExecutionTerminalStatus terminalStatus;

    public TaskExecutionStreamValidator(
            TaskExecutionId taskExecutionId,
            int attempt,
            AgentRunId agentRunId,
            long segmentSequence,
            AgentRunSegmentKind segmentKind) {
        this.taskExecutionId = Objects.requireNonNull(taskExecutionId, "taskExecutionId");
        if (attempt < 1 || segmentSequence < 1) {
            throw new IllegalArgumentException("attempt and segmentSequence must be positive");
        }
        this.attempt = attempt;
        this.agentRunId = Objects.requireNonNull(agentRunId, "agentRunId");
        this.segmentSequence = segmentSequence;
        this.segmentKind = Objects.requireNonNull(segmentKind, "segmentKind");
    }

    public void accept(TaskExecutionEvent event) {
        TaskExecutionEvent required = Objects.requireNonNull(event, "event");
        if (terminalStatus != null) {
            throw invalid("event received after the terminal event");
        }
        if (!taskExecutionId.equals(required.taskExecutionId())
                || attempt != required.attempt()
                || !agentRunId.equals(required.agentRunId())
                || segmentSequence != required.segmentSequence()) {
            throw invalid("event belongs to another Task execution owner or AgentRun Segment");
        }
        if (required.sequence() != nextSequence) {
            throw invalid("event sequence must be contiguous from one; expected " + nextSequence);
        }
        if (!started) {
            if (!(required.payload() instanceof TaskExecutionEventPayload.Started first)
                    || first.segmentKind() != segmentKind) {
                throw invalid("the first event must START the current AgentRun Segment kind");
            }
        } else if (required.payload() instanceof TaskExecutionEventPayload.Started) {
            throw invalid("STARTED can appear only once");
        }
        started = true;
        nextSequence++;
        required.payload().terminalStatus().ifPresent(value -> terminalStatus = value);
    }

    public TaskExecutionTerminalStatus complete() {
        if (terminalStatus == null) {
            throw invalid("the event stream completed without a terminal event");
        }
        return terminalStatus;
    }

    private static ExecutionProtocolException invalid(String message) {
        return new ExecutionProtocolException(message);
    }
}
