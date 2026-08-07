package io.crewscope.infrastructure.artifact;

/** Indicates that a committed filesystem Descriptor violates the ArtifactStore contract. */
final class InvalidArtifactMetadataException extends RuntimeException {

    InvalidArtifactMetadataException(String message) {
        super(message);
    }

    InvalidArtifactMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
