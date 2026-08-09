package io.crewscope.application.execution;

import java.util.Objects;
import java.util.concurrent.Flow;

/** Single-subscriber finite event stream for one invoke or resume segment. */
public record ExecutionHandle(
        RuntimeInvocationId invocationId, Flow.Publisher<ExecutionEvent> events) {

    public ExecutionHandle {
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
        events = new ExecutionEventPublisher(
                invocationId, Objects.requireNonNull(events, "events"));
    }
}
