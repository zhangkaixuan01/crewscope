package io.crewscope.infrastructure.credential;

import java.util.Objects;

/** Ciphertext components persisted in separate constrained database columns. */
record CredentialEnvelope(byte[] ciphertext, byte[] nonce, byte[] authenticationTag) {

    CredentialEnvelope {
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        authenticationTag = Objects.requireNonNull(authenticationTag, "authenticationTag").clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] authenticationTag() {
        return authenticationTag.clone();
    }
}
