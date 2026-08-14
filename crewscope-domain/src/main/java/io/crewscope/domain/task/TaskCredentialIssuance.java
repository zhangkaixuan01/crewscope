package io.crewscope.domain.task;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Objects;

/** One-time pairing of a persistable grant and the plaintext claims sent to the signer. */
public record TaskCredentialIssuance(
        TaskCredentialGrant grant, TaskTokenClaims claims) {

    public TaskCredentialIssuance {
        grant = Objects.requireNonNull(grant, "grant");
        claims = Objects.requireNonNull(claims, "claims");
        boolean closed = grant.id().equals(claims.grantId())
                && grant.jtiHash().equals(claims.jti().hash())
                && grant.scope().equals(claims.scope())
                && grant.issuedAt().equals(claims.issuedAt())
                && grant.expiresAt().equals(claims.expiresAt());
        if (!closed) {
            throw new DomainValidationException(
                    "taskCredentialIssuance", "grant and signed claims must be exactly closed");
        }
    }

    @Override
    public String toString() {
        return "TaskCredentialIssuance[grantId=" + grant.id()
                + ", claims=[REDACTED_CLAIMS]]";
    }
}
