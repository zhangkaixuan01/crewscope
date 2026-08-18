package io.crewscope.domain.coding;

/** Stable safe failure classes for command, test and acceptance evidence. */
public enum EvidenceFailureClassification {
    COMMAND_START_FAILED,
    COMMAND_TIMED_OUT,
    COMMAND_OUTPUT_LIMIT_EXCEEDED,
    COMMAND_SANDBOX_POLICY_VIOLATION,
    COMMAND_CANCELLED,
    COMMAND_NON_ZERO_EXIT,
    TEST_REPORT_MISSING,
    NO_TESTS_EXECUTED,
    TESTS_FAILED,
    ACCEPTANCE_INCOMPLETE,
    ACCEPTANCE_FAILED
}
