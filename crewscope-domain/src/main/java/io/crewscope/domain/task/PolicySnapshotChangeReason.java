package io.crewscope.domain.task;

/** Reason a new immutable policy snapshot supersedes its parent. */
public enum PolicySnapshotChangeReason {
    TASK_CREATED,
    PLAN_REQUIREMENTS_CHANGED,
    EXECUTOR_CHANGED,
    PROVIDER_BINDING_CHANGED,
    POLICY_PACK_CHANGED,
    RUNTIME_CHANGED,
    MANUAL_REAUTHORIZATION
}
