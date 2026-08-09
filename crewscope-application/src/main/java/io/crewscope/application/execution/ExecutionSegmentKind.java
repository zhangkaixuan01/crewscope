package io.crewscope.application.execution;

/** Identifies whether one finite event stream starts or resumes a logical invocation. */
public enum ExecutionSegmentKind {
    INVOKE,
    RESUME
}
