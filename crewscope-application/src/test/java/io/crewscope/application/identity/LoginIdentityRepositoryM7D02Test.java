package io.crewscope.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.crewscope.domain.identity.AccountIdentityProviderKey;
import io.crewscope.domain.identity.IdentityProviderKey;
import io.crewscope.domain.identity.LocalCredentialMetadata;
import io.crewscope.domain.identity.LocalPasswordHash;
import io.crewscope.domain.identity.LoginIdentity;
import io.crewscope.domain.identity.LoginIdentityConflictException;
import io.crewscope.domain.identity.LoginIdentityId;
import io.crewscope.domain.identity.LoginIdentityKey;
import io.crewscope.domain.identity.LoginIdentitySubject;
import io.crewscope.domain.identity.UserAccountId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M7-D02 application ports and the two identity unique coordinates. */
class LoginIdentityRepositoryM7D02Test {

    private static final UtcTimestamp NOW =
            UtcTimestamp.from(Instant.parse("2026-08-28T09:00:00Z"));

    @Test
    void oneAccountCanOwnDifferentProviderIdentities() {
        IdentityIndex index = new IdentityIndex();
        UserAccountId accountId = UserAccountId.generate();
        LoginIdentity local = LoginIdentity.local(LoginIdentityId.generate(), accountId, NOW);
        LoginIdentity oidc = external(accountId, "oidc/corporate", "Subject-42");

        index.create(local);
        index.create(oidc);

        assertEquals(2, index.size());
        assertEquals(local.id(), index.byIdentityKey(local.identityKey()).id());
        assertEquals(oidc.id(), index.byAccountProviderKey(oidc.accountProviderKey()).id());
    }

    @Test
    void providerSubjectCannotBelongToAnotherAccount() {
        IdentityIndex index = new IdentityIndex();
        LoginIdentity first = external(UserAccountId.generate(), "oidc/corporate", "Subject-42");
        index.create(first);

        LoginIdentityConflictException conflict = assertThrows(
                LoginIdentityConflictException.class,
                () -> index.create(external(
                        UserAccountId.generate(), "oidc/corporate", "Subject-42")));

        assertTrue(conflict.error().details().isEmpty());
        assertEquals(1, index.size());
    }

    @Test
    void accountCannotOwnTwoSubjectsForTheSameProvider() {
        IdentityIndex index = new IdentityIndex();
        UserAccountId accountId = UserAccountId.generate();
        index.create(external(accountId, "oidc/corporate", "Subject-42"));

        assertThrows(
                LoginIdentityConflictException.class,
                () -> index.create(external(accountId, "oidc/corporate", "Subject-99")));
        assertEquals(1, index.size());
    }

    @Test
    void repositoryPortsUseTypedKeysAndKeepHashOutOfMetadataMethods() throws Exception {
        Method identityLookup = LoginIdentityRepository.class.getMethod(
                "findByIdentityKey", LoginIdentityKey.class);
        Method providerLookup = LoginIdentityRepository.class.getMethod(
                "findByAccountProviderKey", AccountIdentityProviderKey.class);

        assertEquals(java.util.Optional.class, identityLookup.getReturnType());
        assertEquals(java.util.Optional.class, providerLookup.getReturnType());
        assertTrue(java.util.Arrays.stream(LocalCredentialMetadataRepository.class.getDeclaredMethods())
                .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                .noneMatch(LocalPasswordHash.class::equals));
        assertTrue(java.util.Arrays.stream(LocalCredentialMetadataRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .noneMatch(name -> name.toLowerCase().contains("hash")
                        || name.toLowerCase().contains("password")));
    }

    @Test
    void metadataPortExposesOnlyNonSecretAggregateShape() {
        assertTrue(java.util.Arrays.stream(LocalCredentialMetadata.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .noneMatch(LocalPasswordHash.class::equals));
        assertFalse(java.util.Arrays.stream(LocalCredentialMetadataRepository.class.getDeclaredMethods())
                .anyMatch(method -> method.getReturnType().equals(String.class)));
    }

    private static LoginIdentity external(
            UserAccountId accountId, String provider, String subject) {
        return LoginIdentity.external(
                LoginIdentityId.generate(),
                accountId,
                new IdentityProviderKey(provider),
                new LoginIdentitySubject(subject),
                NOW);
    }

    /** Executable model of the two V31 unique indexes used by the repository contract. */
    private static final class IdentityIndex {

        private final Map<LoginIdentityKey, LoginIdentity> byIdentityKey = new HashMap<>();
        private final Map<AccountIdentityProviderKey, LoginIdentity> byAccountProviderKey =
                new HashMap<>();

        void create(LoginIdentity identity) {
            if (byIdentityKey.containsKey(identity.identityKey())
                    || byAccountProviderKey.containsKey(identity.accountProviderKey())) {
                throw new LoginIdentityConflictException();
            }
            byIdentityKey.put(identity.identityKey(), identity);
            byAccountProviderKey.put(identity.accountProviderKey(), identity);
        }

        LoginIdentity byIdentityKey(LoginIdentityKey key) {
            return byIdentityKey.get(key);
        }

        LoginIdentity byAccountProviderKey(AccountIdentityProviderKey key) {
            return byAccountProviderKey.get(key);
        }

        int size() {
            return byIdentityKey.size();
        }
    }
}
