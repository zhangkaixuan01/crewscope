package io.crewscope.domain.task;

/** Typed purpose of an immutable ArtifactStore object referenced by runtime facts. */
public enum RuntimeArtifactKind {
    AGENT_STATE_SNAPSHOT,
    MODEL_RESULT,
    TOOL_RESULT,
    EXECUTION_LOG,
    PLAN_DRAFT,
    TODO_SNAPSHOT
}
