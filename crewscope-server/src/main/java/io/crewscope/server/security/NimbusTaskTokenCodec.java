package io.crewscope.server.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.crewscope.application.task.DecodedTaskToken;
import io.crewscope.application.task.TaskTokenCodec;
import io.crewscope.application.task.TaskTokenScopeFingerprint;
import io.crewscope.domain.runtime.RuntimeEnvironment;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.task.TaskCredentialGrantId;
import io.crewscope.domain.task.TaskTokenClaims;
import io.crewscope.domain.task.TaskTokenJti;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** HS256 JWT codec with explicit key IDs, exact timestamps and a full-scope commitment. */
public final class NimbusTaskTokenCodec implements TaskTokenCodec {

    private static final String TOKEN_TYPE = "crewscope-task+jwt";

    private final String issuer;
    private final String currentKeyId;
    private final Map<String, byte[]> keys;

    public NimbusTaskTokenCodec(String issuer, String currentKeyId, Map<String, String> encodedKeys) {
        this.issuer = requireText(issuer, "issuer", 200);
        this.currentKeyId = requireText(currentKeyId, "currentKeyId", 100);
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        Objects.requireNonNull(encodedKeys, "encodedKeys").forEach((keyId, encoded) -> {
            String normalizedId = requireText(keyId, "keyId", 100);
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(Objects.requireNonNull(encoded, "encodedKey"));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Task Token key must be standard Base64", failure);
            }
            if (secret.length < 32) {
                throw new IllegalArgumentException("Task Token HS256 keys must contain at least 32 bytes");
            }
            decoded.put(normalizedId, secret.clone());
        });
        if (!decoded.containsKey(this.currentKeyId)) {
            throw new IllegalArgumentException("Task Token current key ID must exist in the key ring");
        }
        this.keys = Map.copyOf(decoded);
    }

    @Override
    public String encode(TaskTokenClaims claims) {
        TaskTokenClaims value = Objects.requireNonNull(claims, "claims");
        try {
            JWTClaimsSet payload = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(value.audience())
                    .subject(value.scope().executionPrincipal().principalId().toString())
                    .jwtID(value.jti().reveal())
                    .issueTime(Date.from(value.issuedAt().value()))
                    .expirationTime(Date.from(value.expiresAt().value()))
                    .claim("grant_id", value.grantId().toString())
                    .claim("organization_id", value.scope().workItemScope().organizationId().toString())
                    .claim("environment", value.scope().environment().value())
                    .claim("issued_at_exact", value.issuedAt().toString())
                    .claim("expires_at_exact", value.expiresAt().toString())
                    .claim("scope_sha256", TaskTokenScopeFingerprint.compute(value.scope()))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256)
                            .keyID(currentKeyId)
                            .type(new com.nimbusds.jose.JOSEObjectType(TOKEN_TYPE))
                            .build(),
                    payload);
            jwt.sign(new MACSigner(keys.get(currentKeyId)));
            return jwt.serialize();
        } catch (JOSEException failure) {
            throw new IllegalStateException("Task Token signing failed", failure);
        }
    }

    @Override
    public DecodedTaskToken decode(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(requireToken(token));
            String keyId = requireText(jwt.getHeader().getKeyID(), "keyId", 100);
            byte[] key = keys.get(keyId);
            JWSVerifier verifier = key == null ? null : new MACVerifier(key);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || jwt.getHeader().getType() == null
                    || !TOKEN_TYPE.equals(jwt.getHeader().getType().toString())
                    || verifier == null
                    || !jwt.verify(verifier)) {
                throw invalidToken();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            UtcTimestamp issuedAt = UtcTimestamp.parse(requiredClaim(claims, "issued_at_exact"));
            UtcTimestamp expiresAt = UtcTimestamp.parse(requiredClaim(claims, "expires_at_exact"));
            if (!issuer.equals(claims.getIssuer())
                    || claims.getAudience().size() != 1
                    || claims.getIssueTime() == null
                    || claims.getExpirationTime() == null
                    || !sameJwtInstant(claims.getIssueTime(), issuedAt)
                    || !sameJwtInstant(claims.getExpirationTime(), expiresAt)) {
                throw invalidToken();
            }
            return new DecodedTaskToken(
                    claims.getAudience().get(0),
                    TaskCredentialGrantId.from(requiredClaim(claims, "grant_id")),
                    new TaskTokenJti(requireText(claims.getJWTID(), "jti", 128)),
                    PrincipalId.from(requireText(claims.getSubject(), "subject", 100)),
                    OrganizationId.from(requiredClaim(claims, "organization_id")),
                    new RuntimeEnvironment(requiredClaim(claims, "environment")),
                    requiredClaim(claims, "scope_sha256"),
                    issuedAt,
                    expiresAt);
        } catch (ParseException | JOSEException | IllegalArgumentException failure) {
            throw invalidToken();
        }
    }

    private static boolean sameJwtInstant(Date encoded, UtcTimestamp exact) {
        // JWT NumericDate uses whole seconds; exact PostgreSQL microseconds remain separately signed.
        Instant expected = exact.value().truncatedTo(ChronoUnit.SECONDS);
        return encoded.toInstant().equals(expected);
    }

    private static String requiredClaim(JWTClaimsSet claims, String name) throws ParseException {
        return requireText(claims.getStringClaim(name), name, 500);
    }

    private static String requireToken(String token) {
        return requireText(token, "token", 16384);
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.strip().length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be a bounded non-blank value");
        }
        return value.strip();
    }

    private static DomainValidationException invalidToken() {
        return new DomainValidationException("taskToken", "is invalid or no longer authorized");
    }

    @Override
    public String toString() {
        return "NimbusTaskTokenCodec[issuer=" + issuer + ", keys=[REDACTED]]";
    }
}
