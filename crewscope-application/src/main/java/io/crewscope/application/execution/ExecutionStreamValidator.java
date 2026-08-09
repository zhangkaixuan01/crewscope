package io.crewscope.application.execution;

import java.util.Objects;

/** Stateful validator for invocation identity, ordering, first event and unique stream terminal. */
public final class ExecutionStreamValidator {

    private final RuntimeInvocationId invocationId;
    private long nextSequence = 1;
    private boolean started;
    private ExecutionTerminalStatus terminalStatus;

    public ExecutionStreamValidator(RuntimeInvocationId invocationId) {
        this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    }

    public void accept(ExecutionEvent event) {
        ExecutionEvent required = Objects.requireNonNull(event, "event");
        if (terminalStatus != null) {
            throw new ExecutionProtocolException("event received after the terminal event");
        }
        if (!invocationId.equals(required.invocationId())) {
            throw new ExecutionProtocolException("event belongs to another invocation");
        }
        if (required.sequence() != nextSequence) {
            throw new ExecutionProtocolException(
                    "event sequence must be contiguous from one; expected " + nextSequence);
        }
        if (!started && !(required.payload() instanceof ExecutionEventPayload.Started)) {
            throw new ExecutionProtocolException("the first event must be STARTED");
        }
        if (started && required.payload() instanceof ExecutionEventPayload.Started) {
            throw new ExecutionProtocolException("STARTED can appear only once");
        }
        started = true;
        nextSequence++;
        required.payload().terminalStatus().ifPresent(value -> terminalStatus = value);
    }

    public ExecutionTerminalStatus complete() {
        if (terminalStatus == null) {
            throw new ExecutionProtocolException("the event stream completed without a terminal event");
        }
        return terminalStatus;
    }
}
