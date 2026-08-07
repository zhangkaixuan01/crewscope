package io.crewscope.infrastructure.credential;

/** Safe startup failure that never echoes encoded key material. */
public class CredentialKeyConfigurationException extends RuntimeException {

    public CredentialKeyConfigurationException(String safeMessage) {
        super(safeMessage);
    }
}
