package io.crewscope.application.execution;

/** Indicates an adapter event sequence that violates the stable ExecutionRuntime protocol. */
public final class ExecutionProtocolException extends RuntimeException {

    public ExecutionProtocolException(String message) {
        super(message);
    }
}
