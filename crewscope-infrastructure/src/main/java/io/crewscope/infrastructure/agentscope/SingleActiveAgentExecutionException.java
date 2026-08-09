package io.crewscope.infrastructure.agentscope;

/** Startup failure raised when another M2 CrewScope instance owns Agent execution. */
public final class SingleActiveAgentExecutionException extends IllegalStateException {

    public SingleActiveAgentExecutionException(String message) {
        super(message);
    }
}
