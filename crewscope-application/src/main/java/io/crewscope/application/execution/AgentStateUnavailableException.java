package io.crewscope.application.execution;

/** Cause-free adapter failure safe to pass through AgentScope and runtime logging boundaries. */
public final class AgentStateUnavailableException extends RuntimeException {

    public AgentStateUnavailableException(Throwable ignoredCause) {
        this();
    }

    public AgentStateUnavailableException() {
        super("Agent state is unavailable", null, false, false);
    }
}
