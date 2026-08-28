package io.crewscope.domain.identity;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Objects;

/** Non-secret local password metadata; the encoded hash remains in a restricted store path. */
public final class LocalCredentialMetadata {

    private final LocalCredentialId id;
    private final UserAccountId accountId;
    private final PasswordHashAlgorithm algorithm;
    private final LocalCredentialVersion credentialVersion;
    private final UtcTimestamp passwordChangedAt;
    private final long version;
    private final LifecycleMetadata lifecycle;

    private LocalCredentialMetadata(
            LocalCredentialId id,
            UserAccountId accountId,
            PasswordHashAlgorithm algorithm,
            LocalCredentialVersion credentialVersion,
            UtcTimestamp passwordChangedAt,
            long version,
            LifecycleMetadata lifecycle) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.credentialVersion = Objects.requireNonNull(credentialVersion, "credentialVersion");
        this.passwordChangedAt = requirePasswordChangedAt(passwordChangedAt, lifecycle);
        this.version = requireVersion(version);
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** Derives public metadata from the accepted encoded hash without retaining the hash value. */
    public static LocalCredentialMetadata create(
            LocalCredentialId id,
            UserAccountId accountId,
            LocalPasswordHash passwordHash,
            UtcTimestamp occurredAt) {
        LocalPasswordHash requiredHash = Objects.requireNonNull(passwordHash, "passwordHash");
        requireCurrentWriteAlgorithm(requiredHash.algorithm());
        UtcTimestamp requiredTime = Objects.requireNonNull(occurredAt, "occurredAt");
        return new LocalCredentialMetadata(
                id,
                accountId,
                requiredHash.algorithm(),
                LocalCredentialVersion.initial(),
                requiredTime,
                0,
                LifecycleMetadata.createdAt(requiredTime));
    }

    /** Reconstitutes metadata from non-secret columns only. */
    public static LocalCredentialMetadata reconstitute(
            LocalCredentialId id,
            UserAccountId accountId,
            PasswordHashAlgorithm algorithm,
            LocalCredentialVersion credentialVersion,
            UtcTimestamp passwordChangedAt,
            long version,
            LifecycleMetadata lifecycle) {
        return new LocalCredentialMetadata(
                id,
                accountId,
                algorithm,
                credentialVersion,
                passwordChangedAt,
                version,
                lifecycle);
    }

    /** Advances both optimistic and credential versions after an atomic hash replacement. */
    public LocalCredentialMetadata rotate(
            LocalPasswordHash replacementHash, UtcTimestamp occurredAt) {
        LocalPasswordHash requiredHash = Objects.requireNonNull(replacementHash, "replacementHash");
        requireCurrentWriteAlgorithm(requiredHash.algorithm());
        LifecycleMetadata changedLifecycle = lifecycle.modifiedAt(occurredAt);
        if (version == Long.MAX_VALUE) {
            throw new DomainValidationException("localCredential.metadataVersion", "must not overflow");
        }
        return new LocalCredentialMetadata(
                id,
                accountId,
                requiredHash.algorithm(),
                credentialVersion.next(),
                Objects.requireNonNull(occurredAt, "occurredAt"),
                version + 1,
                changedLifecycle);
    }

    public LocalCredentialId id() {
        return id;
    }

    public UserAccountId accountId() {
        return accountId;
    }

    public PasswordHashAlgorithm algorithm() {
        return algorithm;
    }

    public LocalCredentialVersion credentialVersion() {
        return credentialVersion;
    }

    public UtcTimestamp passwordChangedAt() {
        return passwordChangedAt;
    }

    public long version() {
        return version;
    }

    public LifecycleMetadata lifecycle() {
        return lifecycle;
    }

    @Override
    public String toString() {
        return "LocalCredentialMetadata[id="
                + id
                + ", accountId="
                + accountId
                + ", algorithm="
                + algorithm
                + ", credentialVersion="
                + credentialVersion
                + ", version="
                + version
                + "]";
    }

    private static UtcTimestamp requirePasswordChangedAt(
            UtcTimestamp value, LifecycleMetadata lifecycle) {
        UtcTimestamp required = Objects.requireNonNull(value, "passwordChangedAt");
        LifecycleMetadata requiredLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        if (required.compareTo(requiredLifecycle.createdAt()) < 0
                || required.compareTo(requiredLifecycle.updatedAt()) > 0) {
            throw new DomainValidationException(
                    "localCredential.passwordChangedAt", "must remain inside the lifecycle");
        }
        return required;
    }

    private static long requireVersion(long value) {
        if (value < 0) {
            throw new DomainValidationException(
                    "localCredential.metadataVersion", "must not be negative");
        }
        return value;
    }

    private static void requireCurrentWriteAlgorithm(PasswordHashAlgorithm algorithm) {
        if (!algorithm.isCurrentWriteAlgorithm()) {
            throw new DomainValidationException(
                    "localCredential.algorithm", "must use the current write algorithm");
        }
    }
}
