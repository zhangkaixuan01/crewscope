package io.crewscope.domain.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConnectionGrantTest {

    @Test
    void supportsUserTeamAndOrganizationOwnershipShapes() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        Connection user = fixture.connection(
                ProviderOwner.user(fixture.owner), Optional.empty());
        Connection team = fixture.teamConnection(Optional.empty());
        Connection organization = fixture.connection(
                ProviderOwner.organization(fixture.organizationId), Optional.empty());

        assertEquals(ProviderOwnerType.USER, user.owner().type());
        assertEquals(ProviderOwnerType.TEAM, team.owner().type());
        assertEquals(ProviderOwnerType.ORGANIZATION, organization.owner().type());
        assertTrue(user.isUsableAt(ProviderDomainFixture.T1));
        assertTrue(team.isUsableAt(ProviderDomainFixture.T1));
        assertTrue(organization.isUsableAt(ProviderDomainFixture.T1));
    }

    @Test
    void connectionSuspendReactivateAndRevokeAreVersioned() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        Connection connection = fixture.teamConnection(Optional.empty());

        Connection suspended = connection.suspend(
                0, fixture.owner, ProviderDomainFixture.T1);
        Connection active = suspended.activate(
                1, fixture.owner, ProviderDomainFixture.T2);
        Connection revoked = active.revoke(
                2, fixture.owner, "Access removed", ProviderDomainFixture.T3);

        assertFalse(suspended.isUsableAt(ProviderDomainFixture.T1));
        assertTrue(active.isUsableAt(ProviderDomainFixture.T2));
        assertEquals(ConnectionStatus.REVOKED, revoked.status());
        assertEquals(3, revoked.version());
        assertThrows(
                OptimisticLockConflictException.class,
                () -> connection.suspend(1, fixture.owner, ProviderDomainFixture.T1));
        assertThrows(
                InvalidStateTransitionException.class,
                () -> revoked.activate(3, fixture.owner, ProviderDomainFixture.T3));
    }

    @Test
    void connectionExpiresOnlyAtItsDeadline() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        Connection connection = fixture.teamConnection(Optional.of(ProviderDomainFixture.T2));

        assertThrows(
                DomainValidationException.class,
                () -> connection.expire(0, fixture.owner, ProviderDomainFixture.T1));
        Connection expired = connection.expire(
                0, fixture.owner, ProviderDomainFixture.T2);

        assertEquals(ConnectionStatus.EXPIRED, expired.status());
        assertFalse(expired.isUsableAt(ProviderDomainFixture.T2));
    }

    @Test
    void grantComputesCapabilityAndResourceIntersection() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        Connection connection = fixture.teamConnection(Optional.empty());
        ProviderAccessScope granted = ProviderDomainFixture.access(
                ProviderCapabilities.of("source.read", "source.write"),
                "repository:crewscope",
                "repository:other");
        ConnectionGrant grant = fixture.grant(
                connection, connection.owner(), granted, Optional.empty());
        ProviderAccessScope requested = ProviderDomainFixture.access(
                ProviderCapabilities.of("source.read", "pull-request.create"),
                "repository:crewscope",
                "repository:forbidden");

        ProviderAccessScope effective = grant
                .effectiveAccess(requested, connection, ProviderDomainFixture.T1)
                .orElseThrow();

        assertEquals(
                ProviderCapabilities.of("source.read"), effective.capabilities());
        assertEquals(
                ProviderResourceScope.of("repository:crewscope"), effective.resources());
    }

    @Test
    void onlyOrganizationOwnerCanDelegateToANarrowerOwner() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        ProviderAccessScope access = ProviderDomainFixture.access(
                ProviderCapabilities.of("source.read"), "repository:crewscope");
        Connection organizationConnection = fixture.connection(
                ProviderOwner.organization(fixture.organizationId), Optional.empty());

        ConnectionGrant delegated = fixture.grant(
                organizationConnection,
                ProviderOwner.team(fixture.team.team()),
                access,
                Optional.empty());

        assertEquals(ProviderOwnerType.TEAM, delegated.grantee().type());
        assertThrows(
                DomainValidationException.class,
                () -> fixture.grant(
                        fixture.teamConnection(Optional.empty()),
                        ProviderOwner.organization(fixture.organizationId),
                        access,
                        Optional.empty()));
    }

    @Test
    void revokedOrExpiredGrantImmediatelyRemovesEffectiveAccess() {
        ProviderDomainFixture fixture = ProviderDomainFixture.create();
        Connection connection = fixture.teamConnection(Optional.empty());
        ProviderAccessScope access = ProviderDomainFixture.access(
                ProviderCapabilities.of("source.read"), "repository:crewscope");
        ConnectionGrant grant = fixture.grant(
                connection, connection.owner(), access, Optional.of(ProviderDomainFixture.T2));
        ConnectionGrant revoked = grant.revoke(
                0, fixture.owner, "No longer needed", ProviderDomainFixture.T1);
        ConnectionGrant expired = grant.expire(
                0, fixture.owner, ProviderDomainFixture.T2);

        assertTrue(grant.effectiveAccess(
                        access, connection, ProviderDomainFixture.T1)
                .isPresent());
        assertTrue(revoked.effectiveAccess(
                        access, connection, ProviderDomainFixture.T1)
                .isEmpty());
        assertTrue(expired.effectiveAccess(
                        access, connection, ProviderDomainFixture.T2)
                .isEmpty());
        assertThrows(
                InvalidStateTransitionException.class,
                () -> revoked.expire(1, fixture.owner, ProviderDomainFixture.T2));
    }
}
