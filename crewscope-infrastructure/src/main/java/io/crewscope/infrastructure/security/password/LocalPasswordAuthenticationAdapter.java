package io.crewscope.infrastructure.security.password;

import io.crewscope.application.identity.LocalCredentialAuthenticationMaterial;
import io.crewscope.application.identity.LocalCredentialStore;
import io.crewscope.application.identity.LocalPasswordAuthentication;
import io.crewscope.application.identity.LocalPasswordVerification;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.PasswordPolicy;
import io.crewscope.domain.identity.PasswordPolicyResult;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Current Argon2id writer and approved legacy reader with Dummy matching and CAS Rehash. */
public final class LocalPasswordAuthenticationAdapter implements LocalPasswordAuthentication {

    private static final Pattern APPROVED_ARGON2 = Pattern.compile(
            "\\{argon2id}\\$argon2id\\$v=19\\$m=(?:19456,t=2|32768,t=(?:2|3)),p=1"
                    + "\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    private static final Pattern APPROVED_BCRYPT =
            Pattern.compile("\\{bcrypt}\\$2[aby]\\$(10|11|12)\\$[./A-Za-z0-9]{53}");

    private final PasswordEncoder encoder;
    private final PasswordHashAdmissionExecutor admission;
    private final LocalCredentialStore credentials;
    private final PasswordPolicy policy;
    private final Clock clock;
    private final LocalPasswordHash dummyHash;

    public LocalPasswordAuthenticationAdapter(
            PasswordEncoder encoder,
            PasswordHashAdmissionExecutor admission,
            LocalCredentialStore credentials,
            Clock clock) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.policy = PasswordPolicy.standard();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dummyHash = new LocalPasswordHash(encoder.encode(randomDummyPassword()));
        if (!isApprovedEncoding(dummyHash)) {
            throw new IllegalStateException("Current password encoder produced an invalid format");
        }
    }

    @Override
    public CompletionStage<LocalPasswordHash> encodeForStorage(String rawPassword) {
        PasswordPolicyResult decision = policy.evaluateForRegistration(rawPassword);
        if (!decision.isAccepted()) {
            throw new DomainValidationException("password", policyReason(decision));
        }
        return admission.submit(() -> currentHash(rawPassword));
    }

    @Override
    public CompletionStage<LocalPasswordVerification> verify(
            String rawPassword,
            Optional<LocalCredentialAuthenticationMaterial> credential,
            boolean accountCanAuthenticate) {
        if (!policy.evaluateForAuthentication(rawPassword).isAccepted()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    LocalPasswordVerification.inputRejected());
        }
        Optional<LocalCredentialAuthenticationMaterial> candidate =
                Objects.requireNonNull(credential, "credential")
                        .filter(ignored -> accountCanAuthenticate)
                        .filter(LocalCredentialAuthenticationMaterial::isUsable)
                        .filter(this::hasApprovedEncoding);
        LocalPasswordHash selected = candidate
                .flatMap(LocalCredentialAuthenticationMaterial::passwordHash)
                .orElse(dummyHash);
        return admission.submit(() -> verifyAdmitted(rawPassword, candidate, selected));
    }

    private LocalPasswordVerification verifyAdmitted(
            String rawPassword,
            Optional<LocalCredentialAuthenticationMaterial> candidate,
            LocalPasswordHash selected) {
        boolean matched;
        try {
            matched = encoder.matches(rawPassword, selected.encodedValue());
        } catch (RuntimeException malformed) {
            // Shape checks route malformed rows to Dummy before admission; fail closed on provider faults.
            return LocalPasswordVerification.invalidCredentials();
        }
        if (candidate.isEmpty() || !matched) {
            return LocalPasswordVerification.invalidCredentials();
        }
        if (!encoder.upgradeEncoding(selected.encodedValue())) {
            return LocalPasswordVerification.authenticated(
                    LocalPasswordVerification.Upgrade.NOT_REQUIRED);
        }

        LocalCredentialAuthenticationMaterial authenticated = candidate.orElseThrow();
        LocalPasswordHash replacementHash = currentHash(rawPassword);
        LocalCredentialMetadata current = authenticated.metadata();
        LocalCredentialMetadata replacement = current.rotate(
                replacementHash, nextPasswordChangeTime(current));
        boolean upgraded = credentials.rotateIfUnchanged(
                replacement, replacementHash, current.version());
        return LocalPasswordVerification.authenticated(upgraded
                ? LocalPasswordVerification.Upgrade.REHASHED
                : LocalPasswordVerification.Upgrade.SKIPPED_CONCURRENT_CHANGE);
    }

    private LocalPasswordHash currentHash(String rawPassword) {
        LocalPasswordHash hash = new LocalPasswordHash(encoder.encode(rawPassword));
        if (!isApprovedEncoding(hash)
                || !hash.algorithm().isCurrentWriteAlgorithm()
                || encoder.upgradeEncoding(hash.encodedValue())) {
            throw new IllegalStateException("Current password encoder produced an invalid format");
        }
        return hash;
    }

    private boolean hasApprovedEncoding(LocalCredentialAuthenticationMaterial material) {
        return material.passwordHash().filter(LocalPasswordAuthenticationAdapter::isApprovedEncoding)
                .isPresent();
    }

    private static boolean isApprovedEncoding(LocalPasswordHash hash) {
        String encoded = hash.encodedValue();
        return switch (hash.algorithm()) {
            case ARGON2ID -> APPROVED_ARGON2.matcher(encoded).matches();
            case BCRYPT -> APPROVED_BCRYPT.matcher(encoded).matches();
        };
    }

    private UtcTimestamp nextPasswordChangeTime(LocalCredentialMetadata current) {
        Instant minimum = current.lifecycle().updatedAt().value().plus(1, ChronoUnit.MICROS);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return UtcTimestamp.from(now.isAfter(minimum) ? now : minimum);
    }

    private static String randomDummyPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String policyReason(PasswordPolicyResult result) {
        return switch (result) {
            case TOO_SHORT -> "must contain at least 12 Unicode code points";
            case TOO_LONG -> "must not exceed 128 Unicode code points";
            case TOO_LARGE -> "must not exceed 512 UTF-8 bytes";
            case COMMON_PASSWORD -> "must not be a blocked common password";
            case INVALID_ENCODING -> "must be valid Unicode text";
            case ACCEPTED -> throw new IllegalArgumentException("Accepted policy has no error reason");
        };
    }
}
