package io.crewscope.application.execution;

/**
 * Encodes the complete in-memory event for conflict detection and emits a sanitized public shape.
 */
@FunctionalInterface
public interface TaskExecutionEventEncoder {

    TaskExecutionEventEncoding encode(TaskExecutionEvent event);
}
