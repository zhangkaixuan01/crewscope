package io.crewscope.application.execution;

/** User- or policy-resolvable reasons for a resumable runtime stop. */
public enum ExecutionInterruptKind {
    CLARIFICATION,
    TOOL_APPROVAL,
    EXTERNAL_EXECUTION,
    POLICY_CHECKPOINT
}
