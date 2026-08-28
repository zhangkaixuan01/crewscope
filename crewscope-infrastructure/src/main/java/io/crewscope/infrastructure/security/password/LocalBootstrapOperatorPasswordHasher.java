package io.crewscope.infrastructure.security.password;

import io.crewscope.application.identity.BootstrapOperatorPasswordHasher;
import io.crewscope.application.identity.BootstrapOperatorPasswordVerification;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.PasswordPolicy;
import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** One-shot deployment hasher that reuses the current local Argon2id writer. */
@Component
public final class LocalBootstrapOperatorPasswordHasher
        implements BootstrapOperatorPasswordHasher {

    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy = PasswordPolicy.standard();

    public LocalBootstrapOperatorPasswordHasher(
            @Qualifier("localCredentialPasswordEncoder") PasswordEncoder encoder) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
    }

    @Override
    public BootstrapOperatorPasswordVerification verify(
            String rawPassword, LocalPasswordHash persistedHash) {
        requireAccepted(rawPassword);
        LocalPasswordHash requiredHash =
                Objects.requireNonNull(persistedHash, "persistedHash");
        try {
            if (!encoder.matches(rawPassword, requiredHash.encodedValue())) {
                return BootstrapOperatorPasswordVerification.MISMATCHED;
            }
            return encoder.upgradeEncoding(requiredHash.encodedValue())
                    ? BootstrapOperatorPasswordVerification.MATCHED_REHASH_REQUIRED
                    : BootstrapOperatorPasswordVerification.MATCHED;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Bootstrap Operator password verification failed");
        }
    }

    @Override
    public LocalPasswordHash encode(String rawPassword) {
        requireAccepted(rawPassword);
        try {
            LocalPasswordHash encoded = new LocalPasswordHash(encoder.encode(rawPassword));
            if (!encoded.algorithm().isCurrentWriteAlgorithm()
                    || encoder.upgradeEncoding(encoded.encodedValue())) {
                throw new IllegalStateException(
                        "Bootstrap Operator password encoder produced an invalid format");
            }
            return encoded;
        } catch (DomainValidationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Bootstrap Operator password encoding failed");
        }
    }

    private void requireAccepted(String rawPassword) {
        if (!passwordPolicy.evaluateForRegistration(rawPassword).isAccepted()) {
            throw new DomainValidationException(
                    "bootstrapOperator.password", "must satisfy the local password policy");
        }
    }
}
