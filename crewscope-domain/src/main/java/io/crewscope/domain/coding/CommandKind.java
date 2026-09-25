package io.crewscope.domain.coding;

/** Stable semantic command slots exposed to the Coding Agent. */
public enum CommandKind {
    PREPARE,
    COMPILE,
    TEST,
    VERIFY,
    FORMAT_CHECK,
    ACCEPTANCE
}
