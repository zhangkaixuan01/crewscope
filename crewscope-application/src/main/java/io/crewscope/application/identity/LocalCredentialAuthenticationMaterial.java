package io.crewscope.application.identity;

import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import java.util.Objects;
import java.util.Optional;

/** Restricted authentication projection; its string form never exposes the encoded Hash. */
public final class LocalCredentialAuthenticationMaterial {

    private final LocalCredentialMetadata metadata;
    private final LocalPasswordHash passwordHash;

    private LocalCredentialAuthenticationMaterial(
            LocalCredentialMetadata metadata, LocalPasswordHash passwordHash) {
        if (metadata == null && passwordHash != null) {
            throw new IllegalArgumentException("Password Hash requires credential metadata");
        }
        this.metadata = metadata;
        this.passwordHash = passwordHash;
    }

    /** Creates usable material only when the secret and non-secret algorithm coordinates agree. */
    public static LocalCredentialAuthenticationMaterial verified(
            LocalCredentialMetadata metadata, LocalPasswordHash passwordHash) {
        LocalCredentialMetadata requiredMetadata = Objects.requireNonNull(metadata, "metadata");
        LocalPasswordHash requiredHash = Objects.requireNonNull(passwordHash, "passwordHash");
        if (requiredMetadata.algorithm() != requiredHash.algorithm()) {
            throw new IllegalArgumentException("Credential algorithm coordinates do not match");
        }
        return new LocalCredentialAuthenticationMaterial(requiredMetadata, requiredHash);
    }

    /** Preserves safe metadata when a persisted Hash cannot enter the trusted reader. */
    public static LocalCredentialAuthenticationMaterial corrupted(
            LocalCredentialMetadata metadata) {
        return new LocalCredentialAuthenticationMaterial(metadata, null);
    }

    /** Represents a row whose algorithm metadata itself cannot be reconstituted. */
    public static LocalCredentialAuthenticationMaterial corrupted() {
        return new LocalCredentialAuthenticationMaterial(null, null);
    }

    public LocalCredentialMetadata metadata() {
        return Objects.requireNonNull(metadata, "Corrupted credential has no usable metadata");
    }

    public Optional<LocalPasswordHash> passwordHash() {
        return Optional.ofNullable(passwordHash);
    }

    public boolean isUsable() {
        return metadata != null && passwordHash != null;
    }

    @Override
    public String toString() {
        return "LocalCredentialAuthenticationMaterial[metadata="
                + (metadata == null ? "UNAVAILABLE" : metadata)
                + ", passwordHash=REDACTED, usable="
                + isUsable()
                + "]";
    }
}
