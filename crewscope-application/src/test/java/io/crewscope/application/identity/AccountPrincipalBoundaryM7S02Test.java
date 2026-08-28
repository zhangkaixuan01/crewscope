package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamJoinMethod;
import io.crewscope.domain.team.TeamMember;
import io.crewscope.domain.team.TeamMemberId;
import io.crewscope.domain.team.TeamScope;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Freezes the M7 Account/LoginIdentity/Binding boundary around the existing Principal graph.
 * Every fixture type remains test-only until M7-D01 through M7-D04 establish production domains.
 */
class AccountPrincipalBoundaryM7S02Test {

    private static final UtcTimestamp NOW = UtcTimestamp.from(Instant.parse("2026-08-28T06:00:00Z"));

    @Test
    void concurrentFirstMappingConvergesOnOneAccountIdentityBindingAndPrincipal() throws Exception {
        BoundaryRegistry registry = new BoundaryRegistry();
        OrganizationId organizationId = OrganizationId.generate();
        int callers = 16;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<ProvisioningResult>> futures = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return registry.provisionExternal(
                                "oidc/corporate", "subject-42", "Kai", organizationId);
                    }))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<ProvisioningResult> results = futures.stream().map(AccountPrincipalBoundaryM7S02Test::get)
                    .toList();
            assertEquals(
                    Set.of(results.get(0).accountId()),
                    results.stream().map(ProvisioningResult::accountId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(
                    Set.of(results.get(0).identityId()),
                    results.stream().map(ProvisioningResult::identityId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(
                    Set.of(results.get(0).bindingId()),
                    results.stream().map(ProvisioningResult::bindingId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(
                    Set.of(results.get(0).principalId()),
                    results.stream().map(ProvisioningResult::principalId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(new Counts(1, 1, 1, 1, 0), registry.counts());
            assertTrue(registry.principal(results.get(0).principalId()).externalIdentity().isEmpty());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneAccountMayOwnDifferentProviderIdentitiesButEachIdentityHasOneOwner() {
        BoundaryRegistry registry = new BoundaryRegistry();
        UserAccount first = registry.registerLocal("Kai", PlatformRole.USER);
        UserAccount second = registry.registerLocal("Lin", PlatformRole.USER);

        LoginIdentity local = registry.identity(first.id(), "local");
        LoginIdentity oidc = registry.linkIdentity(first.id(), "oidc/corporate", "subject-42");

        assertEquals(first.id().value().toString(), local.subject());
        assertEquals(first.id(), oidc.accountId());
        assertEquals(
                Set.of("local", "oidc/corporate"),
                registry.identities(first.id()).stream()
                        .map(LoginIdentity::provider)
                        .collect(java.util.stream.Collectors.toSet()));
        BoundaryViolation identityOwnerConflict = assertThrows(
                BoundaryViolation.class,
                () -> registry.linkIdentity(second.id(), "oidc/corporate", "subject-42"));
        assertTrue(identityOwnerConflict.getMessage().contains("provider and subject"));
        BoundaryViolation providerConflict = assertThrows(
                BoundaryViolation.class,
                () -> registry.linkIdentity(first.id(), "oidc/corporate", "subject-other"));
        assertTrue(providerConflict.getMessage().contains("one identity per provider"));
    }

    @Test
    void requestOrganizationCannotProvisionOrSelectAnUnboundPrincipal() {
        BoundaryRegistry registry = new BoundaryRegistry();
        OrganizationId organizationA = OrganizationId.generate();
        OrganizationId organizationB = OrganizationId.generate();
        UserAccount account = registry.registerLocal("Kai", PlatformRole.USER);
        AccountOrganizationBinding binding = registry.bindNewPrincipal(account.id(), organizationA);
        Counts before = registry.counts();

        assertEquals(binding.principalId(), registry.resolve(account.id(), organizationA).id());
        BoundaryViolation crossOrganization = assertThrows(
                BoundaryViolation.class, () -> registry.resolve(account.id(), organizationB));
        assertTrue(crossOrganization.getMessage().contains("explicit AccountOrganizationBinding"));
        assertEquals(before, registry.counts());

        UserAccount anotherAccount = registry.registerLocal("Lin", PlatformRole.USER);
        BoundaryViolation principalOwnerConflict = assertThrows(
                BoundaryViolation.class,
                () -> registry.bindExistingPrincipal(
                        anotherAccount.id(), organizationA, binding.principalId()));
        assertTrue(principalOwnerConflict.getMessage().contains("already bound"));
    }

    @Test
    void bootstrapOperatorUpgradePreservesPrincipalMembershipAndLegacyReferences() {
        BoundaryRegistry registry = new BoundaryRegistry();
        OrganizationId organizationId = OrganizationId.generate();
        TeamId teamId = TeamId.generate();
        Principal legacyOperator = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "CrewScope Operator",
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamMember existingMember = TeamMember.join(
                TeamMemberId.generate(),
                new TeamScope(organizationId, teamId),
                legacyOperator,
                TeamJoinMethod.BOOTSTRAP,
                NOW);
        registry.importPrincipal(legacyOperator);
        registry.importMember(existingMember);
        Counts before = registry.counts();

        OperatorUpgrade first = registry.upgradeBootstrapOperator(organizationId, "crewscope-monitor");
        OperatorUpgrade repeated = registry.upgradeBootstrapOperator(organizationId, "crewscope-monitor");

        assertEquals(first, repeated);
        assertEquals(legacyOperator.id(), first.principalId());
        assertEquals(Set.of(PlatformRole.OPERATOR), first.account().roles());
        assertEquals("local", first.localIdentity().provider());
        assertEquals(first.account().id().value().toString(), first.localIdentity().subject());
        assertEquals(
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                registry.principal(legacyOperator.id()).externalIdentity());
        assertEquals(legacyOperator.id(), registry.member(existingMember.id()).userPrincipalId());
        assertEquals(existingMember.id(), registry.member(existingMember.id()).id());
        assertEquals(new Counts(
                before.accounts() + 1,
                before.identities() + 1,
                before.bindings() + 1,
                before.principals(),
                before.members()), registry.counts());
        assertEquals(
                legacyOperator.id(), registry.resolve(first.account().id(), organizationId).id());
    }

    @Test
    void bootstrapUpgradeRejectsWrongPrincipalTypeScopeAndStatus() {
        OrganizationId organizationId = OrganizationId.generate();

        assertInvalidLegacyPrincipal(Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Wrong type",
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                PrincipalVisibility.ORGANIZATION,
                NOW));
        assertInvalidLegacyPrincipal(Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(organizationId, TeamId.generate()),
                PrincipalType.USER,
                Optional.empty(),
                "Wrong scope",
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                PrincipalVisibility.TEAM,
                NOW));
        Principal disabled = Principal.create(
                        PrincipalId.generate(),
                        PrincipalScope.organization(organizationId),
                        PrincipalType.USER,
                        Optional.empty(),
                        "Disabled",
                        Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                        PrincipalVisibility.ORGANIZATION,
                        NOW)
                .transitionTo(io.crewscope.domain.identity.PrincipalStatus.DISABLED, NOW);
        assertInvalidLegacyPrincipal(disabled);
    }

    @Test
    void incompatibleLegacyMappingRollsBackTheWholeFirstProvisioningTransaction() {
        BoundaryRegistry registry = new BoundaryRegistry();
        OrganizationId organizationId = OrganizationId.generate();
        registry.importPrincipal(Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.SERVICE,
                Optional.empty(),
                "Conflicting service",
                Optional.of(new ExternalIdentity("oidc/corporate", "subject-42")),
                PrincipalVisibility.ORGANIZATION,
                NOW));
        Counts before = registry.counts();

        BoundaryViolation violation = assertThrows(
                BoundaryViolation.class,
                () -> registry.provisionExternal(
                        "oidc/corporate", "subject-42", "Kai", organizationId));

        assertTrue(violation.getMessage().contains("organization-scoped active USER"));
        assertEquals(before, registry.counts());
    }

    @Test
    void principalAndTeamMemberRemainIndependentFromLoginAccountTypes() {
        assertFalse(Arrays.stream(Principal.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getSimpleName)
                .anyMatch(name -> name.contains("Account") || name.contains("LoginIdentity")));
        assertFalse(Arrays.stream(TeamMember.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getSimpleName)
                .anyMatch(name -> name.contains("Account") || name.contains("LoginIdentity")));
    }

    private static void assertInvalidLegacyPrincipal(Principal principal) {
        BoundaryRegistry registry = new BoundaryRegistry();
        registry.importPrincipal(principal);
        BoundaryViolation violation = assertThrows(
                BoundaryViolation.class,
                () -> registry.upgradeBootstrapOperator(
                        principal.scope().organizationId(), "crewscope-monitor"));
        assertTrue(violation.getMessage().contains("organization-scoped active USER"));
        assertEquals(new Counts(0, 0, 0, 1, 0), registry.counts());
    }

    private static ProvisioningResult get(Future<ProvisioningResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("concurrent identity mapping did not complete", exception);
        }
    }

    private enum AccountStatus {
        ACTIVE,
        LOCKED,
        DISABLED,
        ARCHIVED
    }

    private enum PlatformRole {
        USER,
        OPERATOR
    }

    private enum BindingStatus {
        ACTIVE,
        DISABLED
    }

    private record AccountId(UUID value) {
        private AccountId {
            Objects.requireNonNull(value, "value");
        }

        static AccountId generate() {
            return new AccountId(UUID.randomUUID());
        }
    }

    private record LoginIdentityId(UUID value) {
        private LoginIdentityId {
            Objects.requireNonNull(value, "value");
        }

        static LoginIdentityId generate() {
            return new LoginIdentityId(UUID.randomUUID());
        }
    }

    private record BindingId(UUID value) {
        private BindingId {
            Objects.requireNonNull(value, "value");
        }

        static BindingId generate() {
            return new BindingId(UUID.randomUUID());
        }
    }

    private record UserAccount(
            AccountId id,
            String displayName,
            AccountStatus status,
            Set<PlatformRole> roles) {
        private UserAccount {
            Objects.requireNonNull(id, "id");
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(status, "status");
            roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
            if (roles.isEmpty()) {
                throw new BoundaryViolation("an Account requires a platform role");
            }
        }

        boolean canLogin() {
            return status == AccountStatus.ACTIVE;
        }
    }

    private record LoginIdentity(
            LoginIdentityId id,
            AccountId accountId,
            String provider,
            String subject) {
        private LoginIdentity {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(accountId, "accountId");
            provider = requireText(provider, "provider");
            subject = requireText(subject, "subject");
        }
    }

    private record AccountOrganizationBinding(
            BindingId id,
            AccountId accountId,
            OrganizationId organizationId,
            PrincipalId principalId,
            BindingStatus status) {
        private AccountOrganizationBinding {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(status, "status");
        }

        boolean canEnterOrganization() {
            return status == BindingStatus.ACTIVE;
        }
    }

    private record IdentityKey(String provider, String subject) {}

    private record AccountProviderKey(AccountId accountId, String provider) {}

    private record AccountOrganizationKey(AccountId accountId, OrganizationId organizationId) {}

    private record OrganizationPrincipalKey(
            OrganizationId organizationId, PrincipalId principalId) {}

    private record ProvisioningResult(
            AccountId accountId,
            LoginIdentityId identityId,
            BindingId bindingId,
            PrincipalId principalId) {}

    private record OperatorUpgrade(
            UserAccount account,
            LoginIdentity localIdentity,
            AccountOrganizationBinding binding,
            PrincipalId principalId) {}

    private record Counts(
            int accounts,
            int identities,
            int bindings,
            int principals,
            int members) {}

    /** Atomic in-memory model of the unique indexes and transaction used by M7-D04/D07. */
    private static final class BoundaryRegistry {

        private final Map<AccountId, UserAccount> accounts = new LinkedHashMap<>();
        private final Map<IdentityKey, LoginIdentity> identities = new LinkedHashMap<>();
        private final Map<AccountProviderKey, LoginIdentityId> identityByAccountProvider =
                new LinkedHashMap<>();
        private final Map<AccountOrganizationKey, AccountOrganizationBinding> bindings =
                new LinkedHashMap<>();
        private final Map<OrganizationPrincipalKey, BindingId> bindingByPrincipal =
                new LinkedHashMap<>();
        private final Map<PrincipalId, Principal> principals = new LinkedHashMap<>();
        private final Map<TeamMemberId, TeamMember> members = new LinkedHashMap<>();
        private final Map<PrincipalId, AccountId> bootstrapUpgradeAccounts = new LinkedHashMap<>();

        synchronized ProvisioningResult provisionExternal(
                String provider,
                String subject,
                String displayName,
                OrganizationId organizationId) {
            return transaction(() -> provisionExternalInTransaction(
                    provider, subject, displayName, organizationId));
        }

        private ProvisioningResult provisionExternalInTransaction(
                String provider,
                String subject,
                String displayName,
                OrganizationId organizationId) {
            IdentityKey key = identityKey(provider, subject);
            LoginIdentity identity = identities.get(key);
            UserAccount account;
            if (identity == null) {
                account = createAccount(displayName, EnumSet.of(PlatformRole.USER));
                identity = addIdentity(account.id(), key.provider(), key.subject());
            } else {
                account = requiredAccount(identity.accountId());
            }
            AccountOrganizationBinding binding = bindings.get(
                    new AccountOrganizationKey(account.id(), organizationId));
            if (binding == null) {
                Principal principal = findLegacyPrincipal(organizationId, key)
                        .map(existing -> requireCompatibleUser(existing, organizationId))
                        .orElseGet(() -> createPrincipal(displayName, organizationId));
                binding = bindExistingPrincipal(account.id(), organizationId, principal.id());
            }
            return new ProvisioningResult(
                    account.id(), identity.id(), binding.id(), binding.principalId());
        }

        synchronized UserAccount registerLocal(String displayName, PlatformRole role) {
            UserAccount account = createAccount(displayName, EnumSet.of(role));
            addIdentity(account.id(), "local", account.id().value().toString());
            return account;
        }

        synchronized LoginIdentity linkIdentity(
                AccountId accountId, String provider, String subject) {
            requiredAccount(accountId);
            return addIdentity(accountId, provider, subject);
        }

        synchronized AccountOrganizationBinding bindNewPrincipal(
                AccountId accountId, OrganizationId organizationId) {
            UserAccount account = requiredAccount(accountId);
            AccountOrganizationKey key = new AccountOrganizationKey(accountId, organizationId);
            AccountOrganizationBinding existing = bindings.get(key);
            if (existing != null) {
                return existing;
            }
            Principal principal = createPrincipal(account.displayName(), organizationId);
            return bindExistingPrincipal(accountId, organizationId, principal.id());
        }

        synchronized AccountOrganizationBinding bindExistingPrincipal(
                AccountId accountId,
                OrganizationId organizationId,
                PrincipalId principalId) {
            requiredAccount(accountId);
            Principal principal = requireCompatibleUser(requiredPrincipal(principalId), organizationId);
            AccountOrganizationKey accountKey = new AccountOrganizationKey(accountId, organizationId);
            AccountOrganizationBinding existing = bindings.get(accountKey);
            if (existing != null) {
                if (!existing.principalId().equals(principalId)) {
                    throw new BoundaryViolation("an Account and Organization already use another Principal");
                }
                return existing;
            }
            OrganizationPrincipalKey principalKey = new OrganizationPrincipalKey(organizationId, principalId);
            if (bindingByPrincipal.containsKey(principalKey)) {
                throw new BoundaryViolation("the Organization Principal is already bound to another Account");
            }
            AccountOrganizationBinding binding = new AccountOrganizationBinding(
                    BindingId.generate(), accountId, organizationId, principal.id(), BindingStatus.ACTIVE);
            bindings.put(accountKey, binding);
            bindingByPrincipal.put(principalKey, binding.id());
            return binding;
        }

        synchronized Principal resolve(AccountId accountId, OrganizationId requestedOrganizationId) {
            UserAccount account = requiredAccount(accountId);
            if (!account.canLogin()) {
                throw new BoundaryViolation("the Account cannot log in");
            }
            AccountOrganizationBinding binding = bindings.get(
                    new AccountOrganizationKey(accountId, requestedOrganizationId));
            if (binding == null) {
                throw new BoundaryViolation(
                        "entering an Organization requires an explicit AccountOrganizationBinding");
            }
            if (!binding.canEnterOrganization()) {
                throw new BoundaryViolation("the AccountOrganizationBinding is disabled");
            }
            return requireCompatibleUser(requiredPrincipal(binding.principalId()), requestedOrganizationId);
        }

        synchronized OperatorUpgrade upgradeBootstrapOperator(
                OrganizationId organizationId, String bootstrapSubject) {
            return transaction(() -> upgradeBootstrapOperatorInTransaction(
                    organizationId, bootstrapSubject));
        }

        private OperatorUpgrade upgradeBootstrapOperatorInTransaction(
                OrganizationId organizationId, String bootstrapSubject) {
            IdentityKey legacyKey = identityKey("bootstrap", bootstrapSubject);
            Principal principal = findLegacyPrincipal(organizationId, legacyKey)
                    .orElseThrow(() -> new BoundaryViolation("the exact Bootstrap Principal does not exist"));
            requireCompatibleUser(principal, organizationId);
            AccountId existingAccountId = bootstrapUpgradeAccounts.get(principal.id());
            if (existingAccountId != null) {
                return operatorUpgrade(existingAccountId, organizationId, principal.id());
            }
            UserAccount account = createAccount(principal.displayName(), EnumSet.of(PlatformRole.OPERATOR));
            LoginIdentity local = addIdentity(account.id(), "local", account.id().value().toString());
            AccountOrganizationBinding binding =
                    bindExistingPrincipal(account.id(), organizationId, principal.id());
            bootstrapUpgradeAccounts.put(principal.id(), account.id());
            return new OperatorUpgrade(account, local, binding, principal.id());
        }

        synchronized void importPrincipal(Principal principal) {
            Principal previous = principals.putIfAbsent(principal.id(), principal);
            if (previous != null) {
                throw new BoundaryViolation("the Principal ID already exists");
            }
        }

        synchronized void importMember(TeamMember member) {
            requiredPrincipal(member.userPrincipalId());
            TeamMember previous = members.putIfAbsent(member.id(), member);
            if (previous != null) {
                throw new BoundaryViolation("the TeamMember ID already exists");
            }
        }

        synchronized LoginIdentity identity(AccountId accountId, String provider) {
            LoginIdentityId id = identityByAccountProvider.get(
                    new AccountProviderKey(accountId, requireText(provider, "provider")));
            if (id == null) {
                throw new BoundaryViolation("the Account identity does not exist");
            }
            return identities.values().stream()
                    .filter(value -> value.id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }

        synchronized List<LoginIdentity> identities(AccountId accountId) {
            return identities.values().stream()
                    .filter(identity -> identity.accountId().equals(accountId))
                    .toList();
        }

        synchronized Principal principal(PrincipalId principalId) {
            return requiredPrincipal(principalId);
        }

        synchronized TeamMember member(TeamMemberId memberId) {
            TeamMember member = members.get(memberId);
            if (member == null) {
                throw new BoundaryViolation("the TeamMember does not exist");
            }
            return member;
        }

        synchronized Counts counts() {
            return new Counts(
                    accounts.size(), identities.size(), bindings.size(), principals.size(), members.size());
        }

        private UserAccount createAccount(String displayName, Set<PlatformRole> roles) {
            UserAccount account = new UserAccount(
                    AccountId.generate(), displayName, AccountStatus.ACTIVE, roles);
            accounts.put(account.id(), account);
            return account;
        }

        private LoginIdentity addIdentity(AccountId accountId, String provider, String subject) {
            IdentityKey identityKey = identityKey(provider, subject);
            LoginIdentity existingIdentity = identities.get(identityKey);
            if (existingIdentity != null) {
                if (!existingIdentity.accountId().equals(accountId)) {
                    throw new BoundaryViolation("the provider and subject already belong to another Account");
                }
                return existingIdentity;
            }
            AccountProviderKey accountProviderKey =
                    new AccountProviderKey(accountId, identityKey.provider());
            if (identityByAccountProvider.containsKey(accountProviderKey)) {
                throw new BoundaryViolation("an Account may have only one identity per provider");
            }
            LoginIdentity identity = new LoginIdentity(
                    LoginIdentityId.generate(), accountId, identityKey.provider(), identityKey.subject());
            identities.put(identityKey, identity);
            identityByAccountProvider.put(accountProviderKey, identity.id());
            return identity;
        }

        private Principal createPrincipal(String displayName, OrganizationId organizationId) {
            Principal principal = Principal.create(
                    PrincipalId.generate(),
                    PrincipalScope.organization(organizationId),
                    PrincipalType.USER,
                    Optional.empty(),
                    displayName,
                    Optional.empty(),
                    PrincipalVisibility.ORGANIZATION,
                    NOW);
            principals.put(principal.id(), principal);
            return principal;
        }

        private Optional<Principal> findLegacyPrincipal(
                OrganizationId organizationId, IdentityKey identityKey) {
            ExternalIdentity legacy = new ExternalIdentity(identityKey.provider(), identityKey.subject());
            List<Principal> matches = principals.values().stream()
                    .filter(principal -> principal.scope().organizationId().equals(organizationId))
                    .filter(principal -> principal.externalIdentity().filter(legacy::equals).isPresent())
                    .toList();
            if (matches.size() > 1) {
                throw new BoundaryViolation("the legacy external identity is ambiguous");
            }
            return matches.stream().findFirst();
        }

        private OperatorUpgrade operatorUpgrade(
                AccountId accountId, OrganizationId organizationId, PrincipalId principalId) {
            return new OperatorUpgrade(
                    requiredAccount(accountId),
                    identity(accountId, "local"),
                    bindings.get(new AccountOrganizationKey(accountId, organizationId)),
                    principalId);
        }

        private UserAccount requiredAccount(AccountId accountId) {
            UserAccount account = accounts.get(accountId);
            if (account == null) {
                throw new BoundaryViolation("the Account does not exist");
            }
            return account;
        }

        private Principal requiredPrincipal(PrincipalId principalId) {
            Principal principal = principals.get(principalId);
            if (principal == null) {
                throw new BoundaryViolation("the Principal does not exist");
            }
            return principal;
        }

        private <T> T transaction(Supplier<T> work) {
            Map<AccountId, UserAccount> accountsBefore = new LinkedHashMap<>(accounts);
            Map<IdentityKey, LoginIdentity> identitiesBefore = new LinkedHashMap<>(identities);
            Map<AccountProviderKey, LoginIdentityId> accountProvidersBefore =
                    new LinkedHashMap<>(identityByAccountProvider);
            Map<AccountOrganizationKey, AccountOrganizationBinding> bindingsBefore =
                    new LinkedHashMap<>(bindings);
            Map<OrganizationPrincipalKey, BindingId> principalBindingsBefore =
                    new LinkedHashMap<>(bindingByPrincipal);
            Map<PrincipalId, Principal> principalsBefore = new LinkedHashMap<>(principals);
            Map<PrincipalId, AccountId> upgradesBefore = new LinkedHashMap<>(bootstrapUpgradeAccounts);
            try {
                return work.get();
            } catch (RuntimeException exception) {
                restore(accounts, accountsBefore);
                restore(identities, identitiesBefore);
                restore(identityByAccountProvider, accountProvidersBefore);
                restore(bindings, bindingsBefore);
                restore(bindingByPrincipal, principalBindingsBefore);
                restore(principals, principalsBefore);
                restore(bootstrapUpgradeAccounts, upgradesBefore);
                throw exception;
            }
        }

        private static <K, V> void restore(Map<K, V> target, Map<K, V> snapshot) {
            target.clear();
            target.putAll(snapshot);
        }

        private static Principal requireCompatibleUser(
                Principal principal, OrganizationId organizationId) {
            boolean compatible = principal.type() == PrincipalType.USER
                    && principal.scope().organizationId().equals(organizationId)
                    && principal.scope().teamId().isEmpty()
                    && principal.canAct();
            if (!compatible) {
                throw new BoundaryViolation(
                        "a binding requires an organization-scoped active USER Principal");
            }
            return principal;
        }
    }

    private static IdentityKey identityKey(String provider, String subject) {
        return new IdentityKey(requireText(provider, "provider"), requireText(subject, "subject"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BoundaryViolation(field + " must not be blank");
        }
        return value.strip();
    }

    private static final class BoundaryViolation extends RuntimeException {

        private BoundaryViolation(String message) {
            super(message);
        }
    }
}
