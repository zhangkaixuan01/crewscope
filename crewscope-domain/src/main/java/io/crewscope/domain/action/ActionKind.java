package io.crewscope.domain.action;

/** Shared external action vocabulary with explicit delivery-boundary enforcement. */
public enum ActionKind {
    PUSH_BRANCH,
    CREATE_DRAFT_PR,
    NOTIFY_COLLABORATION
}
