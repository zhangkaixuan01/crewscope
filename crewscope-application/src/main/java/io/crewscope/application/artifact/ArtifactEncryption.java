package io.crewscope.application.artifact;

/** Encryption applied by the concrete storage implementation. */
public enum ArtifactEncryption {
    NONE,
    SERVER_SIDE,
    ENVELOPE
}
