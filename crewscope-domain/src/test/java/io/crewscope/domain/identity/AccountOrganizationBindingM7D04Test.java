package io.crewscope.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountOrganizationBindingM7D04Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T08:00:00Z"));
    private static final UtcTimestamp LATER =
            UtcTimestamp.from(Instant.parse("2026-08-28T08:01:00Z"));

    @Test
    void bindsAnActiveAccountToAnActiveOrganizationUser() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal principal = user(organizationId, Optional.empty());

        AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(), account, organizationId, principal, NOW);

        assertEquals(account.id(), binding.accountId());
        assertEquals(organizationId, binding.organizationId());
        assertEquals(principal.id(), binding.principalId());
        assertEquals(AccountOrganizationBindingStatus.ACTIVE, binding.status());
        assertTrue(binding.isUsable());
        assertTrue(binding.isCompatibleWith(principal));
        assertEquals(0, binding.version());
        assertEquals(NOW, binding.lifecycle().createdAt());
    }

    @Test
    void rejectsEveryAccountStateThatCannotAuthenticate() {
        OrganizationId organizationId = OrganizationId.generate();
        Principal principal = user(organizationId, Optional.empty());
        for (AccountStatus status : new AccountStatus[] {
            AccountStatus.LOCKED, AccountStatus.DISABLED, AccountStatus.ARCHIVED
        }) {
            UserAccount inactive = UserAccount.reconstitute(
                    UserAccountId.generate(),
                    new Username("user-" + status.name().toLowerCase()),
                    status.name().toLowerCase() + "@example.com",
                    NormalizedEmail.fromDisplayValue(
                            status.name().toLowerCase() + "@example.com"),
                    "Inactive user",
                    status,
                    PlatformRole.USER,
                    SecurityVersion.initial(),
                    0,
                    LifecycleMetadata.createdAt(NOW));
            assertThrows(
                    DomainValidationException.class,
                    () -> AccountOrganizationBinding.bind(
                            AccountOrganizationBindingId.generate(),
                            inactive,
                            organizationId,
                            principal,
                            NOW));
        }
    }

    @Test
    void rejectsServiceAgentTeamScopeAndCrossOrganizationPrincipals() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal service = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Service",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        Principal agent = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.PERSONAL_AGENT,
                Optional.of(PrincipalId.generate()),
                "Agent",
                Optional.empty(),
                PrincipalVisibility.PRIVATE,
                NOW);
        Principal teamUser = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, TeamId.generate()),
                PrincipalType.USER,
                Optional.empty(),
                "Team user",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                NOW);
        Principal crossOrganization = user(OrganizationId.generate(), Optional.empty());

        for (Principal invalid : new Principal[] {service, agent, teamUser, crossOrganization}) {
            assertThrows(
                    DomainValidationException.class,
                    () -> AccountOrganizationBinding.bind(
                            AccountOrganizationBindingId.generate(),
                            account,
                            organizationId,
                            invalid,
                            NOW));
        }
    }

    @Test
    void rejectsEveryInactivePrincipalState() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        for (PrincipalStatus status : new PrincipalStatus[] {
            PrincipalStatus.SUSPENDED, PrincipalStatus.DISABLED, PrincipalStatus.ARCHIVED
        }) {
            Principal inactive = Principal.reconstitute(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    "Inactive user",
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    status,
                    0,
                    LifecycleMetadata.createdAt(NOW));
            assertThrows(
                    DomainValidationException.class,
                    () -> AccountOrganizationBinding.bind(
                            AccountOrganizationBindingId.generate(),
                            account,
                            organizationId,
                            inactive,
                            NOW));
        }
    }

    @Test
    void exposesBothMandatoryUniqueCoordinates() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal principal = user(organizationId, Optional.empty());
        AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(), account, organizationId, principal, NOW);

        assertEquals(
                new AccountOrganizationKey(account.id(), organizationId),
                binding.accountOrganizationKey());
        assertEquals(
                new OrganizationPrincipalKey(organizationId, principal.id()),
                binding.organizationPrincipalKey());
    }

    @Test
    void disablingAndReactivatingPreservesImmutableEndsAndAdvancesLifecycle() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal principal = user(organizationId, Optional.empty());
        AccountOrganizationBinding original = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(), account, organizationId, principal, NOW);

        AccountOrganizationBinding disabled = original.disable(LATER);
        AccountOrganizationBinding active = disabled.activate(account, principal, LATER);

        assertFalse(disabled.isUsable());
        assertTrue(active.isUsable());
        assertEquals(original.id(), active.id());
        assertEquals(original.accountId(), active.accountId());
        assertEquals(original.organizationId(), active.organizationId());
        assertEquals(original.principalId(), active.principalId());
        assertEquals(2, active.version());
        assertEquals(LATER, active.lifecycle().updatedAt());
    }

    @Test
    void activationCannotReplaceEitherBindingEnd() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal principal = user(organizationId, Optional.empty());
        AccountOrganizationBinding disabled = AccountOrganizationBinding.bind(
                        AccountOrganizationBindingId.generate(),
                        account,
                        organizationId,
                        principal,
                        NOW)
                .disable(LATER);

        assertThrows(
                DomainValidationException.class,
                () -> disabled.activate(account("lin", "lin@example.com"), principal, LATER));
        assertThrows(
                DomainValidationException.class,
                () -> disabled.activate(account, user(organizationId, Optional.empty()), LATER));
    }

    @Test
    void statusMachineRejectsNoOpTransitions() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal principal = user(organizationId, Optional.empty());
        AccountOrganizationBinding active = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organizationId,
                principal,
                NOW);
        AccountOrganizationBinding disabled = active.disable(LATER);

        assertThrows(
                InvalidStateTransitionException.class,
                () -> active.activate(account, principal, LATER));
        assertThrows(InvalidStateTransitionException.class, () -> disabled.disable(LATER));
    }

    @Test
    void versionValidationFailsClosedAtBothBounds() {
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal principal = user(organizationId, Optional.empty());
        assertThrows(
                DomainValidationException.class,
                () -> AccountOrganizationBinding.reconstitute(
                        AccountOrganizationBindingId.generate(),
                        account.id(),
                        organizationId,
                        principal.id(),
                        AccountOrganizationBindingStatus.ACTIVE,
                        -1,
                        LifecycleMetadata.createdAt(NOW)));

        AccountOrganizationBinding exhausted = AccountOrganizationBinding.reconstitute(
                AccountOrganizationBindingId.generate(),
                account.id(),
                organizationId,
                principal.id(),
                AccountOrganizationBindingStatus.ACTIVE,
                Long.MAX_VALUE,
                LifecycleMetadata.createdAt(NOW));
        assertThrows(DomainValidationException.class, () -> exhausted.disable(LATER));
    }

    @Test
    void bootstrapPrincipalIsBoundInPlaceAndKeepsLegacyExternalIdentity() {
        OrganizationId organizationId = OrganizationId.generate();
        ExternalIdentity legacy = new ExternalIdentity("bootstrap", "crewscope-monitor");
        Principal principal = user(organizationId, Optional.of(legacy));
        AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account("operator", "operator@example.com"),
                organizationId,
                principal,
                NOW);

        assertEquals(principal.id(), binding.principalId());
        assertEquals(Optional.of(legacy), principal.externalIdentity());
    }

    @Test
    void conflictIsStableAndContainsNoIdentityCoordinates() {
        AccountOrganizationBindingConflictException conflict =
                new AccountOrganizationBindingConflictException();

        assertEquals(
                DomainErrorCode.ACCOUNT_ORGANIZATION_BINDING_CONFLICT,
                conflict.error().code());
        assertTrue(conflict.error().details().isEmpty());
        assertFalse(conflict.getMessage().contains("accountId"));
        assertFalse(conflict.getMessage().contains("principalId"));
    }

    @Test
    void principalAndTeamMemberKeepNoReverseAccountDependency() throws Exception {
        assertNoReverseDependency(Principal.class);
        assertNoReverseDependency(io.crewscope.domain.team.TeamMember.class);
    }

    @Test
    void generatedBindingIdsAreStableCanonicalAggregateIds() {
        AccountOrganizationBindingId first = AccountOrganizationBindingId.generate();
        AccountOrganizationBindingId second = AccountOrganizationBindingId.generate();

        assertNotEquals(first, second);
        assertEquals(first, AccountOrganizationBindingId.from(first.toString()));
    }

    private static void assertNoReverseDependency(Class<?> type) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            String fieldType = field.getGenericType().getTypeName();
            assertFalse(
                    Arrays.stream(new String[] {
                                UserAccount.class.getName(),
                                UserAccountId.class.getName(),
                                LoginIdentity.class.getName(),
                                AccountOrganizationBinding.class.getName()
                            })
                            .anyMatch(fieldType::contains),
                    () -> type.getSimpleName() + " must not depend on " + fieldType);
        }
    }

    private static UserAccount account(String username, String email) {
        return UserAccount.register(UserAccountId.generate(), username, email, username, NOW);
    }

    private static Principal user(
            OrganizationId organizationId, Optional<ExternalIdentity> externalIdentity) {
        return Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "User",
                externalIdentity,
                PrincipalVisibility.ORGANIZATION,
                NOW);
    }
}
