package io.crewscope.domain.coding;

/** Platform-observed terminal condition of one controlled command process. */
public enum CommandTermination {
    EXITED,
    TIMED_OUT,
    START_FAILED,
    OUTPUT_LIMIT_EXCEEDED,
    SANDBOX_POLICY_VIOLATION,
    CANCELLED
}
