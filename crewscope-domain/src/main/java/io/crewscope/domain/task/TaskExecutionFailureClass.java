package io.crewscope.domain.task;

/** Stable failure category used by retry policy without retaining raw provider errors. */
public enum TaskExecutionFailureClass {
    TRANSIENT(true),
    RATE_LIMITED(true),
    TIMEOUT(true),
    RUNTIME_UNAVAILABLE(true),
    MODEL_UNAVAILABLE(true),
    TOOL_UNAVAILABLE(true),
    RESOURCE_EXHAUSTED(true),
    RECOVERY_INTERRUPTED(true),
    VALIDATION(false),
    AUTHENTICATION(false),
    AUTHORIZATION(false),
    POLICY_VIOLATION(false),
    CAPABILITY_UNSUPPORTED(false),
    NOT_FOUND(false),
    CONFLICT(false),
    INTERNAL(false);

    private final boolean retryable;

    TaskExecutionFailureClass(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
