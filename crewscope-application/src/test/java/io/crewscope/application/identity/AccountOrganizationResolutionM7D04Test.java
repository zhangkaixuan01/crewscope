package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.AccountOrganizationBinding;
import io.crewscope.domain.identity.AccountOrganizationBindingConflictException;
import io.crewscope.domain.identity.AccountOrganizationBindingId;
import io.crewscope.domain.identity.AccountOrganizationKey;
import io.crewscope.domain.identity.AccountStatus;
import io.crewscope.domain.identity.ExternalIdentity;
import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentityStatus;
import io.crewscope.domain.identity.OrganizationPrincipalKey;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalStatus;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.identity.UserAccount;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.audit.LifecycleMetadata;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class AccountOrganizationResolutionM7D04Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T08:00:00Z"));

    @Test
    void resolvesOnlyTheCompleteExistingIdentityChain() {
        Fixture fixture = new Fixture();
        AccountOrganizationResolution resolution = fixture.resolver
                .resolveExisting(fixture.account, fixture.identity, fixture.organizationId)
                .orElseThrow();

        assertSame(fixture.account, resolution.account());
        assertSame(fixture.identity, resolution.loginIdentity());
        assertEquals(fixture.binding.id(), resolution.binding().id());
        assertSame(fixture.principal, resolution.principal());
        assertEquals(1, fixture.bindings.reads);
        assertEquals(1, fixture.principalReads);
    }

    @Test
    void unboundOrganizationIsRejectedWithoutCreatingAnyFact() {
        Fixture fixture = new Fixture();
        OrganizationId requested = OrganizationId.generate();
        int bindingsBefore = fixture.bindings.size();
        int principalsBefore = fixture.principals.size();

        assertTrue(fixture.resolver
                .resolveExisting(fixture.account, fixture.identity, requested)
                .isEmpty());
        assertEquals(bindingsBefore, fixture.bindings.size());
        assertEquals(principalsBefore, fixture.principals.size());
        assertEquals(0, fixture.bindings.creates);
        assertEquals(0, fixture.principalReads);
    }

    @Test
    void resolverRejectsAccountIdentityAndBindingStateIntersection() {
        Fixture fixture = new Fixture();
        UserAccount locked = fixture.account.transitionTo(AccountStatus.LOCKED, NOW);
        LoginIdentity disabledIdentity =
                fixture.identity.transitionTo(LoginIdentityStatus.DISABLED, NOW);
        AccountOrganizationBinding disabledBinding = fixture.binding.disable(NOW);

        assertTrue(fixture.resolver
                .resolveExisting(locked, fixture.identity, fixture.organizationId)
                .isEmpty());
        assertTrue(fixture.resolver
                .resolveExisting(fixture.account, disabledIdentity, fixture.organizationId)
                .isEmpty());
        fixture.bindings.replace(disabledBinding);
        assertTrue(fixture.resolver
                .resolveExisting(fixture.account, fixture.identity, fixture.organizationId)
                .isEmpty());
    }

    @Test
    void identityFromAnotherAccountCannotSelectTheBinding() {
        Fixture fixture = new Fixture();
        UserAccount another = account("lin", "lin@example.com");
        LoginIdentity anotherIdentity = LoginIdentity.local(
                LoginIdentityId.generate(), another.id(), NOW);

        assertTrue(fixture.resolver
                .resolveExisting(fixture.account, anotherIdentity, fixture.organizationId)
                .isEmpty());
        assertEquals(0, fixture.bindings.reads);
    }

    @Test
    void resolverFailsClosedForMissingOrIncompatiblePrincipal() {
        Fixture fixture = new Fixture();
        fixture.principals.clear();
        assertTrue(fixture.resolver
                .resolveExisting(fixture.account, fixture.identity, fixture.organizationId)
                .isEmpty());

        List<Principal> invalid = List.of(
                principal(
                        fixture.principal.id(),
                        fixture.organizationId,
                        PrincipalType.SERVICE,
                        PrincipalStatus.ACTIVE,
                        false),
                principal(
                        fixture.principal.id(),
                        fixture.organizationId,
                        PrincipalType.USER,
                        PrincipalStatus.ACTIVE,
                        true),
                principal(
                        fixture.principal.id(),
                        fixture.organizationId,
                        PrincipalType.USER,
                        PrincipalStatus.SUSPENDED,
                        false),
                principal(
                        fixture.principal.id(),
                        fixture.organizationId,
                        PrincipalType.USER,
                        PrincipalStatus.DISABLED,
                        false),
                principal(
                        fixture.principal.id(),
                        fixture.organizationId,
                        PrincipalType.USER,
                        PrincipalStatus.ARCHIVED,
                        false));
        for (Principal principal : invalid) {
            fixture.principals.put(principal.id(), principal);
            assertTrue(fixture.resolver
                    .resolveExisting(fixture.account, fixture.identity, fixture.organizationId)
                    .isEmpty());
        }
    }

    @Test
    void resolverCannotCallTheLegacyProvisioningPortByConstruction() {
        assertEquals(
                Set.of("findById"),
                java.util.Arrays.stream(OrganizationPrincipalReader.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(Collectors.toSet()));
        assertFalse(java.util.Arrays.stream(
                        AccountOrganizationPrincipalResolver.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(PrincipalRepository.class)));
    }

    @Test
    void concurrentIdenticalBindingsConvergeOnOneCommittedBinding() throws Exception {
        InMemoryBindingRepository repository = new InMemoryBindingRepository();
        AccountOrganizationBindingService service =
                new AccountOrganizationBindingService(repository);
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount account = account("kai", "kai@example.com");
        Principal principal = user(organizationId);
        int callers = 16;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<AccountOrganizationBinding>> futures = IntStream.range(0, callers)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return service.bindExistingPrincipal(
                                AccountOrganizationBindingId.generate(),
                                account,
                                organizationId,
                                principal,
                                NOW);
                    }))
                    .toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            Set<AccountOrganizationBindingId> ids = new java.util.HashSet<>();
            for (Future<AccountOrganizationBinding> future : futures) {
                ids.add(future.get(10, TimeUnit.SECONDS).id());
            }
            assertEquals(1, ids.size());
            assertEquals(1, repository.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void bothUniqueCoordinatesRejectDifferentOwnership() {
        InMemoryBindingRepository repository = new InMemoryBindingRepository();
        AccountOrganizationBindingService service =
                new AccountOrganizationBindingService(repository);
        OrganizationId organizationId = OrganizationId.generate();
        UserAccount first = account("kai", "kai@example.com");
        UserAccount second = account("lin", "lin@example.com");
        Principal firstPrincipal = user(organizationId);
        Principal secondPrincipal = user(organizationId);
        service.bindExistingPrincipal(
                AccountOrganizationBindingId.generate(),
                first,
                organizationId,
                firstPrincipal,
                NOW);

        assertThrows(
                AccountOrganizationBindingConflictException.class,
                () -> service.bindExistingPrincipal(
                        AccountOrganizationBindingId.generate(),
                        first,
                        organizationId,
                        secondPrincipal,
                        NOW));
        assertThrows(
                AccountOrganizationBindingConflictException.class,
                () -> service.bindExistingPrincipal(
                        AccountOrganizationBindingId.generate(),
                        second,
                        organizationId,
                        firstPrincipal,
                        NOW));
        assertEquals(1, repository.size());
    }

    @Test
    void bootstrapPrincipalIsReusedWithoutMembershipOrExternalIdentityMutation() {
        InMemoryBindingRepository repository = new InMemoryBindingRepository();
        AccountOrganizationBindingService service =
                new AccountOrganizationBindingService(repository);
        OrganizationId organizationId = OrganizationId.generate();
        Principal legacy = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "CrewScope Operator",
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        UserAccount operator = UserAccount.bootstrapOperator(
                UserAccountId.generate(),
                "operator",
                "operator@example.com",
                "Operator",
                NOW);

        AccountOrganizationBinding first = service.bindExistingPrincipal(
                AccountOrganizationBindingId.generate(),
                operator,
                organizationId,
                legacy,
                NOW);
        AccountOrganizationBinding repeated = service.bindExistingPrincipal(
                AccountOrganizationBindingId.generate(),
                operator,
                organizationId,
                legacy,
                NOW);

        assertEquals(first.id(), repeated.id());
        assertEquals(legacy.id(), repeated.principalId());
        assertEquals(
                Optional.of(new ExternalIdentity("bootstrap", "crewscope-monitor")),
                legacy.externalIdentity());
        assertEquals(1, repository.size());
        assertFalse(java.util.Arrays.stream(
                        AccountOrganizationBindingService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().getName().contains("TeamMember")));
    }

    private static final class Fixture {
        private final OrganizationId organizationId = OrganizationId.generate();
        private final UserAccount account = account("kai", "kai@example.com");
        private final LoginIdentity identity =
                LoginIdentity.local(LoginIdentityId.generate(), account.id(), NOW);
        private final Principal principal = user(organizationId);
        private final AccountOrganizationBinding binding = AccountOrganizationBinding.bind(
                AccountOrganizationBindingId.generate(),
                account,
                organizationId,
                principal,
                NOW);
        private final InMemoryBindingRepository bindings = new InMemoryBindingRepository();
        private final Map<PrincipalId, Principal> principals = new HashMap<>();
        private int principalReads;
        private final AccountOrganizationPrincipalResolver resolver;

        private Fixture() {
            bindings.create(binding);
            bindings.creates = 0;
            principals.put(principal.id(), principal);
            resolver = new AccountOrganizationPrincipalResolver(bindings, (organization, id) -> {
                principalReads++;
                Principal candidate = principals.get(id);
                if (candidate == null
                        || !candidate.scope().organizationId().equals(organization)) {
                    return Optional.empty();
                }
                return Optional.of(candidate);
            });
        }
    }

    private static final class InMemoryBindingRepository
            implements AccountOrganizationBindingRepository {
        private final Map<AccountOrganizationBindingId, AccountOrganizationBinding> byId =
                new HashMap<>();
        private final Map<AccountOrganizationKey, AccountOrganizationBindingId> byAccount =
                new HashMap<>();
        private final Map<OrganizationPrincipalKey, AccountOrganizationBindingId> byPrincipal =
                new HashMap<>();
        private int reads;
        private int creates;

        @Override
        public synchronized Optional<AccountOrganizationBinding> findById(
                AccountOrganizationBindingId bindingId) {
            return Optional.ofNullable(byId.get(bindingId));
        }

        @Override
        public synchronized Optional<AccountOrganizationBinding> findByAccountOrganizationKey(
                AccountOrganizationKey key) {
            reads++;
            return Optional.ofNullable(byAccount.get(key)).map(byId::get);
        }

        @Override
        public synchronized Optional<AccountOrganizationBinding> findByOrganizationPrincipalKey(
                OrganizationPrincipalKey key) {
            return Optional.ofNullable(byPrincipal.get(key)).map(byId::get);
        }

        @Override
        public synchronized List<AccountOrganizationBinding> findByAccountId(
                UserAccountId accountId) {
            List<AccountOrganizationBinding> result = new ArrayList<>();
            for (AccountOrganizationBinding binding : byId.values()) {
                if (binding.accountId().equals(accountId)) {
                    result.add(binding);
                }
            }
            return List.copyOf(result);
        }

        @Override
        public synchronized AccountOrganizationBinding create(
                AccountOrganizationBinding binding) {
            creates++;
            if (byAccount.containsKey(binding.accountOrganizationKey())
                    || byPrincipal.containsKey(binding.organizationPrincipalKey())) {
                throw new AccountOrganizationBindingConflictException();
            }
            byId.put(binding.id(), binding);
            byAccount.put(binding.accountOrganizationKey(), binding.id());
            byPrincipal.put(binding.organizationPrincipalKey(), binding.id());
            return binding;
        }

        @Override
        public synchronized AccountOrganizationBinding update(
                AccountOrganizationBinding binding, long expectedVersion) {
            AccountOrganizationBinding existing = byId.get(binding.id());
            if (existing == null || existing.version() != expectedVersion) {
                throw new OptimisticLockConflictException(
                        "AccountOrganizationBinding",
                        binding.id(),
                        expectedVersion,
                        existing == null ? 0 : existing.version());
            }
            byId.put(binding.id(), binding);
            return binding;
        }

        private synchronized int size() {
            return byId.size();
        }

        private synchronized void replace(AccountOrganizationBinding binding) {
            byId.put(binding.id(), binding);
        }
    }

    private static UserAccount account(String username, String email) {
        return UserAccount.register(UserAccountId.generate(), username, email, username, NOW);
    }

    private static Principal user(OrganizationId organizationId) {
        return principal(
                PrincipalId.generate(),
                organizationId,
                PrincipalType.USER,
                PrincipalStatus.ACTIVE,
                false);
    }

    private static Principal principal(
            PrincipalId id,
            OrganizationId organizationId,
            PrincipalType type,
            PrincipalStatus status,
            boolean teamScope) {
        return Principal.reconstitute(
                id,
                teamScope
                        ? PrincipalScope.team(organizationId, TeamId.generate())
                        : PrincipalScope.organization(organizationId),
                type,
                Optional.empty(),
                "Principal",
                Optional.empty(),
                teamScope ? PrincipalVisibility.TEAM : PrincipalVisibility.ORGANIZATION,
                status,
                0,
                LifecycleMetadata.createdAt(NOW));
    }
}
