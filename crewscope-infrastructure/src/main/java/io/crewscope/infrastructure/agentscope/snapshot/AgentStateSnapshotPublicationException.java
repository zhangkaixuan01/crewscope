package io.crewscope.infrastructure.agentscope.snapshot;

/** Prevents an inconsistent ArtifactStore response from becoming committed snapshot metadata. */
public final class AgentStateSnapshotPublicationException extends RuntimeException {

    public AgentStateSnapshotPublicationException(String safeMessage) {
        super(safeMessage);
    }
}
