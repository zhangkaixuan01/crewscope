package io.crewscope.agentscope;

/** Sanitized terminal model failure safe to pass through AgentScope's internal log statements. */
public final class SafeModelExecutionException extends RuntimeException {

    private final String safeCode;

    SafeModelExecutionException(String safeCode) {
        super("Model execution failed [" + safeCode + "]", null, false, false);
        this.safeCode = safeCode;
    }

    /** Returns the bounded provider-neutral code without exposing provider exception text. */
    public String safeCode() {
        return safeCode;
    }
}
