package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.DomainErrorCode;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.InvalidStateTransitionException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrincipalTest {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final UtcTimestamp CREATED_AT = UtcTimestamp.parse("2026-08-07T10:00:00Z");

    @Test
    void createsAUserWithCanonicalExternalIdentity() {
        Principal principal = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "  Zhang Kaixuan  ",
                Optional.of(new ExternalIdentity("  oidc  ", "  user-101  ")),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);

        assertEquals(PrincipalType.USER, principal.type());
        assertEquals("Zhang Kaixuan", principal.displayName());
        assertEquals("oidc", principal.externalIdentity().orElseThrow().provider());
        assertEquals(PrincipalStatus.ACTIVE, principal.status());
        assertTrue(principal.canAct());
        assertEquals(0, principal.version());
    }

    @Test
    void supportsEveryStablePrincipalType() {
        PrincipalId owner = PrincipalId.generate();

        for (PrincipalType type : PrincipalType.values()) {
            Optional<PrincipalId> requiredOwner = type.isAgent()
                    ? Optional.of(owner)
                    : Optional.empty();
            Principal principal = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.team(ORGANIZATION_ID, TEAM_ID),
                    type,
                    requiredOwner,
                    type.name(),
                    Optional.empty(),
                    PrincipalVisibility.TEAM,
                    CREATED_AT);

            assertEquals(type, principal.type());
        }
    }

    @Test
    void requiresAgentOwnershipAndRejectsOwnershipForUsersAndServices() {
        DomainValidationException missingOwner = assertThrows(
                DomainValidationException.class,
                () -> Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(ORGANIZATION_ID),
                        PrincipalType.PERSONAL_AGENT,
                        Optional.empty(),
                        "Personal Agent",
                        Optional.empty(),
                        PrincipalVisibility.PRIVATE,
                        CREATED_AT));
        DomainValidationException unexpectedOwner = assertThrows(
                DomainValidationException.class,
                () -> Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(ORGANIZATION_ID),
                        PrincipalType.SERVICE,
                        Optional.of(PrincipalId.generate()),
                        "Service",
                        Optional.empty(),
                        PrincipalVisibility.ORGANIZATION,
                        CREATED_AT));

        assertEquals("principal.ownerPrincipalId", missingOwner.error().details().get("field"));
        assertEquals("principal.ownerPrincipalId", unexpectedOwner.error().details().get("field"));
    }

    @Test
    void rejectsSelfOwnedAgent() {
        PrincipalId id = PrincipalId.generate();

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> Principal.create(
                        id,
                        PrincipalScope.organization(ORGANIZATION_ID),
                        PrincipalType.PERSONAL_AGENT,
                        Optional.of(id),
                        "Personal Agent",
                        Optional.empty(),
                        PrincipalVisibility.PRIVATE,
                        CREATED_AT));

        assertEquals(DomainErrorCode.INVALID_VALUE, failure.error().code());
        assertEquals("principal.ownerPrincipalId", failure.error().details().get("field"));
    }

    @Test
    void requiresATeamForTeamVisibility() {
        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(ORGANIZATION_ID),
                        PrincipalType.USER,
                        Optional.empty(),
                        "User",
                        Optional.empty(),
                        PrincipalVisibility.TEAM,
                        CREATED_AT));

        assertEquals("principal.visibility", failure.error().details().get("field"));
    }

    @Test
    void suspendsDisablesAndReactivatesAccessWithOptimisticVersions() {
        Principal principal = activeUser()
                .transitionTo(
                        PrincipalStatus.SUSPENDED,
                        UtcTimestamp.parse("2026-08-07T10:01:00Z"))
                .transitionTo(
                        PrincipalStatus.DISABLED,
                        UtcTimestamp.parse("2026-08-07T10:02:00Z"))
                .transitionTo(
                        PrincipalStatus.ACTIVE,
                        UtcTimestamp.parse("2026-08-07T10:03:00Z"));

        assertEquals(PrincipalStatus.ACTIVE, principal.status());
        assertEquals(3, principal.version());
        assertTrue(principal.canAct());
        assertEquals(
                UtcTimestamp.parse("2026-08-07T10:03:00Z"),
                principal.lifecycle().updatedAt());
    }

    @Test
    void nonActivePrincipalCannotActAndArchivedPrincipalIsTerminal() {
        Principal archived = activeUser().transitionTo(
                PrincipalStatus.ARCHIVED,
                UtcTimestamp.parse("2026-08-07T10:01:00Z"));

        assertFalse(archived.canAct());
        InvalidStateTransitionException failure = assertThrows(
                InvalidStateTransitionException.class,
                () -> archived.transitionTo(
                        PrincipalStatus.ACTIVE,
                        UtcTimestamp.parse("2026-08-07T10:02:00Z")));
        assertEquals("ARCHIVED", failure.error().details().get("currentState"));
    }

    @Test
    void rejectsOutOfOrderLifecycleChanges() {
        Principal suspended = activeUser().transitionTo(
                PrincipalStatus.SUSPENDED,
                UtcTimestamp.parse("2026-08-07T10:02:00Z"));

        DomainValidationException failure = assertThrows(
                DomainValidationException.class,
                () -> suspended.transitionTo(
                        PrincipalStatus.ACTIVE,
                        UtcTimestamp.parse("2026-08-07T10:01:00Z")));

        assertEquals("lifecycle.updatedAt", failure.error().details().get("field"));
    }

    @Test
    void importedDisabledAgentCanReceiveItsMissingOwnerBeforeActivation() {
        Principal imported = Principal.reconstitute(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.TEAM_AGENT,
                Optional.empty(),
                "Migrated Agent",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                PrincipalStatus.DISABLED,
                0,
                LifecycleMetadata.createdAt(CREATED_AT));

        assertThrows(
                DomainValidationException.class,
                () -> imported.transitionTo(
                        PrincipalStatus.ACTIVE,
                        UtcTimestamp.parse("2026-08-07T10:01:00Z")));

        Principal active = imported
                .assignOwner(
                        PrincipalId.generate(),
                        UtcTimestamp.parse("2026-08-07T10:01:00Z"))
                .transitionTo(
                        PrincipalStatus.ACTIVE,
                        UtcTimestamp.parse("2026-08-07T10:02:00Z"));

        assertTrue(active.ownerPrincipalId().isPresent());
        assertTrue(active.canAct());
        assertEquals(2, active.version());
    }

    private static Principal activeUser() {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(ORGANIZATION_ID),
                PrincipalType.USER,
                Optional.empty(),
                "User",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                CREATED_AT);
    }
}
