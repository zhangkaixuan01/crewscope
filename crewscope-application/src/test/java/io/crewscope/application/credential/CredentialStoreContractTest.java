package io.crewscope.application.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Locks the framework-independent CredentialStore request, authorization and secret contracts. */
class CredentialStoreContractTest {

    private static final UtcTimestamp CREATED_AT =
            UtcTimestamp.parse("2026-08-07T11:00:00Z");
    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final PrincipalId PRINCIPAL_ID = PrincipalId.generate();

    @Test
    void acceptsOnlyCanonicalOrganizationTeamAndPrincipalSubjectShapes() {
        TeamId teamId = TeamId.generate();

        assertEquals(
                ORGANIZATION_ID.value(),
                CredentialSubject.organization(ORGANIZATION_ID).subjectId());
        assertEquals(
                teamId.value(),
                CredentialSubject.team(ORGANIZATION_ID, teamId).subjectId());
        assertEquals(
                PRINCIPAL_ID.value(),
                CredentialSubject.principal(ORGANIZATION_ID, PRINCIPAL_ID).subjectId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CredentialSubject(
                        ORGANIZATION_ID,
                        CredentialSubjectType.TEAM,
                        UUID.randomUUID(),
                        Optional.of(teamId),
                        Optional.empty()));
    }

    @Test
    void normalizesCreateMetadataAndMakesItImmutable() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(" account ", " engineering ");

        CredentialCreateRequest request = request(CredentialId.generate(), metadata);
        metadata.put("later", "change");

        assertEquals(Map.of("account", "engineering"), request.metadata());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.metadata().put("forbidden", "change"));
    }

    @Test
    void keepsPlaintextDefensiveCloseableAndRedacted() {
        byte[] source = "github-secret".getBytes(StandardCharsets.UTF_8);
        CredentialSecret secret = CredentialSecret.of(source);
        source[0] = 'X';
        byte[] firstCopy = secret.copyBytes();
        firstCopy[1] = 'X';

        assertArrayEquals("github-secret".getBytes(StandardCharsets.UTF_8), secret.copyBytes());
        assertEquals("CredentialSecret[REDACTED]", secret.toString());
        assertFalse(secret.toString().contains("github-secret"));

        secret.close();
        assertTrue(secret.isClosed());
        assertThrows(IllegalStateException.class, secret::copyBytes);
    }

    @Test
    void grantsOnlyTenantQualifiedExplicitCredentialCapabilities() {
        CredentialId allowedId = CredentialId.generate();
        CredentialId deniedId = CredentialId.generate();
        CredentialAccessContext access = new CredentialAccessContext(
                ORGANIZATION_ID, PRINCIPAL_ID, Set.of(allowedId), "github:push");

        assertTrue(access.allows(new CredentialReference(ORGANIZATION_ID, allowedId)));
        assertFalse(access.allows(new CredentialReference(ORGANIZATION_ID, deniedId)));
        assertFalse(access.allows(new CredentialReference(OrganizationId.generate(), allowedId)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.allowedCredentialIds().add(deniedId));
    }

    @Test
    void appliesActiveExpiryAndRevocationBoundariesWithoutSecretData() {
        CredentialCreateRequest request = request(CredentialId.generate(), Map.of());
        CredentialDescriptor active = descriptor(
                request,
                CredentialStatus.ACTIVE,
                Optional.of(UtcTimestamp.from(CREATED_AT.value().plus(Duration.ofHours(1)))),
                Optional.empty());
        CredentialDescriptor revoked = descriptor(
                request,
                CredentialStatus.REVOKED,
                Optional.empty(),
                Optional.of(CREATED_AT));

        assertTrue(active.isUsableAt(UtcTimestamp.from(CREATED_AT.value().plusSeconds(3599))));
        assertFalse(active.isUsableAt(UtcTimestamp.from(CREATED_AT.value().plusSeconds(3600))));
        assertFalse(revoked.isUsableAt(CREATED_AT));
        assertNotEquals("github-secret", active.toString());
    }

    private static CredentialCreateRequest request(
            CredentialId credentialId, Map<String, String> metadata) {
        return new CredentialCreateRequest(
                credentialId,
                CredentialSubject.principal(ORGANIZATION_ID, PRINCIPAL_ID),
                "github-main",
                "github",
                Optional.of(UUID.randomUUID()),
                "OAUTH_TOKEN",
                metadata,
                Optional.empty(),
                PRINCIPAL_ID);
    }

    private static CredentialDescriptor descriptor(
            CredentialCreateRequest request,
            CredentialStatus status,
            Optional<UtcTimestamp> expiresAt,
            Optional<UtcTimestamp> revokedAt) {
        return new CredentialDescriptor(
                request.credentialId(),
                request.subject(),
                request.credentialKey(),
                request.providerKey(),
                request.connectionRef(),
                request.credentialType(),
                request.metadata(),
                status,
                expiresAt,
                Optional.empty(),
                revokedAt,
                "dev-key-1",
                "AES-256-GCM",
                "1",
                PRINCIPAL_ID,
                PRINCIPAL_ID,
                CREATED_AT,
                CREATED_AT,
                0,
                0);
    }
}
