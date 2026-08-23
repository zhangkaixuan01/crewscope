package io.crewscope.domain.action;

/** M5 source-delivery write operations with separate execution and reconciliation semantics. */
public enum ActionKind {
    PUSH_BRANCH,
    CREATE_DRAFT_PR
}
