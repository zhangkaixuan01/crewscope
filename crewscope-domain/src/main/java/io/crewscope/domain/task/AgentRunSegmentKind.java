package io.crewscope.domain.task;

/** Reason a finite runtime event stream Segment was opened. */
public enum AgentRunSegmentKind {
    INVOKE,
    RESUME,
    RECOVERY
}
