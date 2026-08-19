package io.crewscope.agentscope.coding;

/** Durable event and Step transition attached to a Coding safe point. */
public enum CodingSpecialistCheckpointKind {
    PROGRESS,
    PAUSED,
    CANCELLED
}
