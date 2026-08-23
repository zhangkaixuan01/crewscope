package io.crewscope.domain.action;

/** Worker operation permitted by a claimed Dispatch epoch. */
public enum ActionClaimMode {
    EXECUTE,
    RECONCILE
}
