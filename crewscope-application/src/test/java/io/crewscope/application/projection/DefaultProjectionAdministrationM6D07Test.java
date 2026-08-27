package io.crewscope.application.projection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.error.PolicyDeniedException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Trusted platform authority and current Principal checks for Projection administration. */
class DefaultProjectionAdministrationM6D07Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-08-28T00:00:00Z");
    private final DefaultProjectionAdministration administration =
            new DefaultProjectionAdministration();

    @Test
    void acceptsOnlyActiveUserAdministratorInTheRequestedOrganization() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal actor = user(organizationId);

        assertDoesNotThrow(() -> administration.requireOrganizationAdministrator(
                organizationId, new TeamAccessContext(actor, true), NOW));
        assertThrows(PolicyDeniedException.class,
                () -> administration.requireOrganizationAdministrator(
                        organizationId, new TeamAccessContext(actor, false), NOW));
        assertThrows(PolicyDeniedException.class,
                () -> administration.requireOrganizationAdministrator(
                        OrganizationId.generate(), new TeamAccessContext(actor, true), NOW));
        assertThrows(PolicyDeniedException.class,
                () -> administration.requireOrganizationAdministrator(
                        organizationId,
                        new TeamAccessContext(
                                actor.transitionTo(
                                        PrincipalStatus.SUSPENDED,
                                        UtcTimestamp.parse("2026-08-28T00:01:00Z")),
                                true),
                        NOW));
        assertThrows(PolicyDeniedException.class,
                () -> administration.requireOrganizationAdministrator(
                        organizationId,
                        new TeamAccessContext(service(organizationId), true),
                        NOW));
    }

    private static Principal user(OrganizationId organizationId) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }

    private static Principal service(OrganizationId organizationId) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Service",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }
}
