package io.crewscope.application.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.credential.CredentialDescriptor;
import io.crewscope.application.credential.CredentialStore;
import io.crewscope.domain.agent.AgentModelPreflightException;
import io.crewscope.domain.agent.AgentModelPreflightRejectionCode;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionHealth;
import io.crewscope.domain.model.ModelConnectionHealthStatus;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionStatus;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CachedModelConnectionAvailabilityVerifierTest {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-23T08:00:00Z");

    @Test
    void cachesOnlyAnExactConnectionAndCredentialVersionAndSupportsExplicitInvalidation() {
        Fixture fixture = fixture(true, Optional.empty());

        fixture.verifier().requireAvailable(fixture.connection(), fixture.actor(), NOW);
        fixture.verifier().requireAvailable(fixture.connection(), fixture.actor(), NOW);

        verify(fixture.store()).describe(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(1, fixture.verifier().cacheSize());

        fixture.verifier().invalidate(fixture.organizationId(), fixture.connection().id());
        fixture.verifier().requireAvailable(fixture.connection(), fixture.actor(), NOW);

        verify(fixture.store(), org.mockito.Mockito.times(2)).describe(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotShareCredentialAvailabilityAcrossRequestingPrincipals() {
        Fixture fixture = fixture(true, Optional.empty());
        PrincipalId anotherActor = PrincipalId.generate();

        fixture.verifier().requireAvailable(fixture.connection(), fixture.actor(), NOW);
        fixture.verifier().requireAvailable(fixture.connection(), anotherActor, NOW);

        verify(fixture.store(), org.mockito.Mockito.times(2)).describe(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(2, fixture.verifier().cacheSize());
    }

    @Test
    void rejectsExpiredCredentialsWithAStablePreflightReason() {
        Fixture fixture = fixture(
                false, Optional.of(UtcTimestamp.parse("2026-08-23T07:59:59Z")));

        AgentModelPreflightException failure = assertThrows(
                AgentModelPreflightException.class,
                () -> fixture.verifier().requireAvailable(
                        fixture.connection(), fixture.actor(), NOW));

        assertEquals(AgentModelPreflightRejectionCode.CREDENTIAL_UNAVAILABLE, failure.reason());
    }

    @Test
    void rejectsBeforeCredentialLookupWhenPersistedHealthIsNotCurrent() {
        Fixture fixture = fixture(true, Optional.empty());
        when(fixture.connection().status()).thenReturn(ModelConnectionStatus.REVOKED);

        AgentModelPreflightException failure = assertThrows(
                AgentModelPreflightException.class,
                () -> fixture.verifier().requireAvailable(
                        fixture.connection(), fixture.actor(), NOW));

        assertEquals(AgentModelPreflightRejectionCode.CONNECTION_UNAVAILABLE, failure.reason());
        verify(fixture.store(), org.mockito.Mockito.never()).describe(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static Fixture fixture(
            boolean credentialUsable, Optional<UtcTimestamp> expiresAt) {
        OrganizationId organizationId = OrganizationId.generate();
        PrincipalId actor = PrincipalId.generate();
        CredentialId credentialId = CredentialId.generate();
        ModelConnectionId connectionId = ModelConnectionId.generate();
        ModelCredentialVersion credentialVersion = new ModelCredentialVersion(3);
        ModelCredentialBinding binding = new ModelCredentialBinding(
                credentialId,
                ModelCredentialSubject.organization(organizationId),
                credentialVersion);
        ModelConnectionHealth health = mock(ModelConnectionHealth.class);
        when(health.status()).thenReturn(ModelConnectionHealthStatus.HEALTHY);
        when(health.isHealthyFor(credentialVersion)).thenReturn(true);
        ModelConnection connection = mock(ModelConnection.class);
        when(connection.organizationId()).thenReturn(organizationId);
        when(connection.id()).thenReturn(connectionId);
        when(connection.version()).thenReturn(7L);
        when(connection.providerKey()).thenReturn(new ModelProviderKey("deepseek"));
        when(connection.status()).thenReturn(ModelConnectionStatus.ACTIVE);
        when(connection.health()).thenReturn(health);
        when(connection.credentialBinding()).thenReturn(binding);

        CredentialDescriptor descriptor = mock(CredentialDescriptor.class);
        when(descriptor.credentialId()).thenReturn(credentialId);
        when(descriptor.subject()).thenReturn(
                io.crewscope.application.credential.CredentialSubject.organization(organizationId));
        when(descriptor.providerKey()).thenReturn("deepseek");
        when(descriptor.connectionRef()).thenReturn(Optional.of(connectionId.value()));
        when(descriptor.credentialType())
                .thenReturn(ModelConnectionCredentialService.API_KEY_CREDENTIAL_TYPE);
        when(descriptor.secretVersion()).thenReturn(credentialVersion.value());
        when(descriptor.expiresAt()).thenReturn(expiresAt);
        when(descriptor.isUsableAt(NOW)).thenReturn(credentialUsable);
        CredentialStore store = mock(CredentialStore.class);
        when(store.describe(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(descriptor));
        CachedModelConnectionAvailabilityVerifier verifier =
                new CachedModelConnectionAvailabilityVerifier(
                        store, Duration.ofSeconds(30), 16);
        return new Fixture(organizationId, actor, connection, store, verifier);
    }

    private record Fixture(
            OrganizationId organizationId,
            PrincipalId actor,
            ModelConnection connection,
            CredentialStore store,
            CachedModelConnectionAvailabilityVerifier verifier) {}
}
