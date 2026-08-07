package io.crewscope.infrastructure.credential;

/** Bounded key rewrap outcome; conflicts are safe concurrent changes that were not overwritten. */
public record CredentialRewrapResult(
        int selected, int rewrapped, int conflicts, long remaining) {

    public CredentialRewrapResult {
        if (selected < 0 || rewrapped < 0 || conflicts < 0 || remaining < 0
                || rewrapped + conflicts != selected) {
            throw new IllegalArgumentException("Credential rewrap result counts are invalid");
        }
    }
}
