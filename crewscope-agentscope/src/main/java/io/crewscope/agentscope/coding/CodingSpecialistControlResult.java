package io.crewscope.agentscope.coding;

/** Acknowledgement for an exact Session control signal. */
public record CodingSpecialistControlResult(boolean accepted, boolean interruptDelivered) {}
