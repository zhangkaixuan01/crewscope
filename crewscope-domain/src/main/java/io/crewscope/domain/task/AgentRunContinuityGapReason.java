package io.crewscope.domain.task;

/** Stable recovery reason recorded when exact AgentState continuation is unavailable. */
public enum AgentRunContinuityGapReason {
    SNAPSHOT_MISSING,
    SNAPSHOT_CORRUPT,
    SNAPSHOT_IDENTITY_MISMATCH,
    REDIS_STATE_LOST,
    UNSAFE_CHECKPOINT
}
